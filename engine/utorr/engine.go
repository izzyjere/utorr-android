package utorr

// #cgo LDFLAGS: -static-libstdc++
import "C"
import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/anacrolix/torrent/storage"
	"go.etcd.io/bbolt"
)

type Status struct {
	Id          string
	Name        string
	Progress    float64 // 0..100
	DownloadBps int64
	UploadBps   int64
	TotalSize   int64
	Downloaded  int64
	Peers       int
	TotalPeers  int
	Seeds       int
	State       string // QUEUED, DOWNLOADING, PAUSED, FINISHED, CHECKING
	SavePath    string
}

type StatusList struct{ items []*Status }

func (s *StatusList) Len() int          { return len(s.items) }
func (s *StatusList) Get(i int) *Status { return s.items[i] }

type Listener interface {
	OnAdded(id string, name string)
	OnRemoved(id string)
	OnStatus(list *StatusList)
	OnError(msg string)
}

type entry struct {
	Id     string `json:"id"`
	Kind   string `json:"kind"`   // "magnet" | "torrent"
	Magnet string `json:"magnet"` // if magnet
	TPath  string `json:"tpath"`  // if torrent (path to stored .torrent)
	Paused bool   `json:"paused"`
}

type registry struct {
	Items []entry `json:"items"`
}

type rateState struct {
	t time.Time
	r int64
	w int64
}

type Engine struct {
	mu       sync.Mutex
	cl       *torrent.Client
	rootDir  string
	session  string
	listener Listener

	torrents map[string]*torrent.Torrent
	paused   map[string]bool
	prev     map[string]rateState

	tickStop chan struct{}
	maxConns int

	db *bbolt.DB
}

func NewEngine() *Engine {
	return &Engine{
		torrents: make(map[string]*torrent.Torrent),
		paused:   make(map[string]bool),
		prev:     make(map[string]rateState),
	}
}

func (e *Engine) Start(rootDir, sessionDir string, maxConns int, l Listener, debug bool) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if e.cl != nil {
		return errors.New("engine already started")
	}
	e.rootDir = rootDir
	e.session = sessionDir
	e.listener = l
	e.maxConns = maxConns

	_ = os.MkdirAll(rootDir, 0o755)
	_ = os.MkdirAll(sessionDir, 0o755)
	_ = os.MkdirAll(filepath.Join(sessionDir, "torrents"), 0o755)

	cfg := torrent.NewDefaultClientConfig()
	cfg.DataDir = rootDir
	cfg.NoUpload = true
	cfg.Seed = false
	cfg.HalfOpenConnsPerTorrent = 8
	cfg.EstablishedConnsPerTorrent = maxConns
	cfg.AcceptPeerConnections = true
	cfg.NoDefaultPortForwarding = true

	// Resume persistence
	pc, err := storage.NewBoltPieceCompletion(sessionDir)
	if err == nil {
		cfg.DefaultStorage = storage.NewFileOpts(storage.NewFileClientOpts{
			ClientBaseDir:   rootDir,
			PieceCompletion: pc,
		})
	}
	cl, err := torrent.NewClient(cfg)
	if err != nil {
		return err
	}
	e.cl = cl

	// Restore previously added torrents
	go e.restore()

	// Status tick
	e.tickStop = make(chan struct{})
	go e.statusLoop()

	// DB
	dbPath := filepath.Join(sessionDir, "utorr.db")
	db, err := bbolt.Open(dbPath, 0o600, &bbolt.Options{Timeout: 1 * time.Second})
	if err != nil {
		return err
	}
	e.db = db

	// init bucket
	_ = e.db.Update(func(tx *bbolt.Tx) error {
		_, err := tx.CreateBucketIfNotExists([]byte("completed"))
		return err
	})

	return nil
}

func (e *Engine) Stop() {
	e.mu.Lock()
	stop := e.tickStop
	cl := e.cl
	e.tickStop = nil
	e.cl = nil
	e.torrents = make(map[string]*torrent.Torrent)
	e.paused = make(map[string]bool)
	e.prev = make(map[string]rateState)
	e.mu.Unlock()

	if stop != nil {
		close(stop)
	}
	if cl != nil {
		cl.Close()
	}
	if e.db != nil {
		e.db.Close()
		e.db = nil
	}
}

// ---------- Add APIs ----------

func (e *Engine) AddMagnet(magnet string) (string, error) {
	cl := e.getClient()
	if cl == nil {
		return "", errors.New("engine not started")
	}
	t, err := cl.AddMagnet(magnet)
	if err != nil {
		return "", err
	}
	id := t.InfoHash().HexString()
	e.attach(id, t, false)

	// persist
	_ = e.upsertEntry(entry{Id: id, Kind: "magnet", Magnet: magnet, Paused: false})

	return id, nil
}

func (e *Engine) AddTorrentBytes(torrentBytes []byte) (string, error) {
	cl := e.getClient()
	if cl == nil {
		return "", errors.New("engine not started")
	}
	mi, err := metainfo.Load(bytes.NewReader(torrentBytes))
	if err != nil {
		return "", err
	}
	infoHash := mi.HashInfoBytes().HexString()

	// store .torrent for restore
	tpath := filepath.Join(e.session, "torrents", infoHash+".torrent")
	_ = os.WriteFile(tpath, torrentBytes, 0o644)

	t, err := cl.AddTorrent(mi)
	if err != nil {
		return "", err
	}
	e.attach(infoHash, t, false)

	_ = e.upsertEntry(entry{Id: infoHash, Kind: "torrent", TPath: tpath, Paused: false})

	return infoHash, nil
}

// ---------- Control APIs ----------

func (e *Engine) Pause(id string) {
	e.mu.Lock()
	e.paused[id] = true
	t := e.torrents[id]
	e.mu.Unlock()

	if t != nil {
		t.DisallowDataDownload()
	}
	_ = e.setPaused(id, true)
}

func (e *Engine) Resume(id string) {
	e.mu.Lock()
	delete(e.paused, id)
	t := e.torrents[id]
	e.mu.Unlock()

	if t != nil {
		go func() {
			<-t.GotInfo()
			if t.BytesCompleted() < t.Length() && !e.isCompleted(id) {
				t.DownloadAll()
			}
			t.AllowDataDownload()
		}()
	}
	_ = e.setPaused(id, false)
}

func (e *Engine) PauseAll() {
	e.mu.Lock()
	ids := make([]string, 0, len(e.torrents))
	for id, t := range e.torrents {
		_ = t
		e.paused[id] = true
		ids = append(ids, id)
	}
	e.mu.Unlock()

	for _, id := range ids {
		e.Pause(id)
	}
}

func (e *Engine) ResumeAll() {
	e.mu.Lock()
	ids := make([]string, 0, len(e.torrents))
	for id := range e.torrents {
		ids = append(ids, id)
	}
	e.mu.Unlock()

	for _, id := range ids {
		e.Resume(id)
	}
}

func (e *Engine) Remove(id string, deleteFiles bool) {
	e.mu.Lock()
	t := e.torrents[id]
	delete(e.torrents, id)
	delete(e.paused, id)
	delete(e.prev, id)
	e.mu.Unlock()

	_ = e.deleteEntry(id)
	_ = e.markDeleted(id)

	if t != nil {
		// best-effort file delete (needs info for exact file list)
		if deleteFiles {
			go e.deleteTorrentFiles(t)
		}
		t.Drop()
	}

	if e.listener != nil {
		e.listener.OnRemoved(id)
	}
}

// ---------- Internals ----------

func (e *Engine) getClient() *torrent.Client {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.cl
}

func (e *Engine) attach(id string, t *torrent.Torrent, paused bool) {
	e.mu.Lock()
	e.torrents[id] = t
	if paused {
		e.paused[id] = true
	}
	e.mu.Unlock()

	go func() {
		<-t.GotInfo()
		t.SetMaxEstablishedConns(e.maxConns)
		if paused {
			t.DisallowDataDownload()
		} else {
			// If already completed, don't start download. Just allow data to seeding.
			if t.BytesCompleted() < t.Length() && !e.isCompleted(id) {
				t.DownloadAll()
			}
			t.AllowDataDownload()
		}
		if e.listener != nil {
			name := t.Name()
			if name == "" {
				name = id
			}
			e.listener.OnAdded(id, name)
		}
	}()
}

func (e *Engine) statusLoop() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-e.tickStop:
			return
		case now := <-ticker.C:
			e.emitStatus(now)
		}
	}
}

func (e *Engine) emitStatus(now time.Time) {
	e.mu.Lock()
	if e.cl == nil || e.listener == nil {
		e.mu.Unlock()
		return
	}
	out := make([]*Status, 0, len(e.torrents))

	for id, t := range e.torrents {
		size := t.Length()
		done := t.BytesCompleted()
		st := t.Stats()

		prev := e.prev[id]
		read := st.ConnStats.BytesReadData.Int64()
		write := st.ConnStats.BytesWrittenData.Int64()

		var downBps, upBps int64
		if !prev.t.IsZero() {
			dt := now.Sub(prev.t).Seconds()
			if dt > 0 {
				downBps = int64(float64(read-prev.r) / dt)
				upBps = int64(float64(write-prev.w) / dt)
			}
		}
		e.prev[id] = rateState{t: now, r: read, w: write}

		name := t.Name()
		if name == "" {
			name = id
		}

		pct := 0.0
		if size > 0 {
			pct = (float64(done) / float64(size)) * 100.0
		}

		state := "QUEUED"
		if e.paused[id] {
			state = "PAUSED"
		} else if size > 0 && done == size {
			state = "FINISHED"
			_ = e.markCompleted(id)
		} else if done > 0 && (size == 0 || done < size) {
			state = "DOWNLOADING"
		} else if t.Info() == nil {
			state = "METADATA"
		}

		out = append(out, &Status{
			Id:          id,
			Name:        name,
			Progress:    pct,
			DownloadBps: downBps,
			UploadBps:   upBps,
			TotalSize:   size,
			Downloaded:  done,
			Peers:       st.ActivePeers,
			TotalPeers:  st.TotalPeers,
			Seeds:       st.ConnectedSeeders,
			State:       state,
			SavePath:    e.rootDir,
		})
	}
	e.mu.Unlock()

	e.listener.OnStatus(&StatusList{items: out})
}

func (e *Engine) deleteTorrentFiles(t *torrent.Torrent) {
	<-t.GotInfo()
	// Delete files listed in torrent info under rootDir.
	// This is safer than guessing. Still best-effort due to path rules.
	info := t.Info()
	if info == nil {
		return
	}
	for _, f := range info.Files {
		// f.Path is []string segments
		p := filepath.Join(append([]string{e.rootDir, info.Name}, f.Path...)...)
		_ = os.Remove(p)
	}
	// try remove now-empty folders
	_ = os.RemoveAll(filepath.Join(e.rootDir, info.Name))
}

// ---------- Registry (restore) ----------

func (e *Engine) regPath() string { return filepath.Join(e.session, "registry.json") }

func (e *Engine) loadRegistry() registry {
	b, err := os.ReadFile(e.regPath())
	if err != nil {
		return registry{}
	}
	var r registry
	_ = json.Unmarshal(b, &r)
	return r
}

func (e *Engine) saveRegistry(r registry) error {
	tmp := e.regPath() + ".tmp"
	b, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, e.regPath())
}

func (e *Engine) upsertEntry(en entry) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	r := e.loadRegistry()
	found := false
	for i := range r.Items {
		if r.Items[i].Id == en.Id {
			r.Items[i] = en
			found = true
			break
		}
	}
	if !found {
		r.Items = append(r.Items, en)
	}
	return e.saveRegistry(r)
}

func (e *Engine) setPaused(id string, paused bool) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	r := e.loadRegistry()
	for i := range r.Items {
		if r.Items[i].Id == id {
			r.Items[i].Paused = paused
			return e.saveRegistry(r)
		}
	}
	return nil
}

func (e *Engine) deleteEntry(id string) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	r := e.loadRegistry()
	out := r.Items[:0]
	for _, it := range r.Items {
		if it.Id != id {
			out = append(out, it)
		}
	}
	r.Items = out
	return e.saveRegistry(r)
}

func (e *Engine) markCompleted(id string) error {
	if e.db == nil {
		return nil
	}
	return e.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket([]byte("completed"))
		if b == nil {
			return nil
		}
		return b.Put([]byte(id), []byte{1})
	})
}

func (e *Engine) isCompleted(id string) bool {
	if e.db == nil {
		return false
	}
	found := false
	_ = e.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket([]byte("completed"))
		if b == nil {
			return nil
		}
		if v := b.Get([]byte(id)); v != nil {
			found = true
		}
		return nil
	})
	return found
}

func (e *Engine) markDeleted(id string) error {
	if e.db == nil {
		return nil
	}
	return e.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket([]byte("completed"))
		if b == nil {
			return nil
		}
		return b.Delete([]byte(id))
	})
}

func (e *Engine) restore() {
	cl := e.getClient()
	if cl == nil {
		return
	}
	r := e.loadRegistry()
	for _, it := range r.Items {
		var t *torrent.Torrent
		var err error

		switch it.Kind {
		case "magnet":
			t, err = cl.AddMagnet(it.Magnet)
		case "torrent":
			b, re := os.ReadFile(it.TPath)
			if re != nil {
				err = re
				break
			}
			mi, re := metainfo.Load(bytes.NewReader(b))
			if re != nil {
				err = re
				break
			}
			t, err = cl.AddTorrent(mi)
		default:
			continue
		}

		if err != nil {
			if e.listener != nil {
				e.listener.OnError("restore " + it.Id + ": " + err.Error())
			}
			continue
		}
		id := it.Id
		if id == "" {
			id = t.InfoHash().HexString()
		}
		e.attach(id, t, it.Paused)
	}
}
