// A stand-in for a drand relay, for testing owndroid-key against the real tle
// without the internet. It serves a quicknet-style chain (bls-unchained-g1-rfc9380,
// a round every 3 seconds) with its own key. Rounds count up with the real clock
// from a genesis 3000 seconds ago, plus an offset in rounds read from a file on
// every request, so a test can move "drand time" forward.
//   fakedrand ADDR OFFSET_FILE      prints the chain hash and genesis time, then serves
package main

import (
	"bytes"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	common "github.com/drand/drand/v2/common"
	"github.com/drand/drand/v2/common/chain"
	"github.com/drand/drand/v2/crypto"
	"github.com/drand/go-clients/client"
	"github.com/drand/kyber/util/random"
	json "github.com/nikkolasg/hexjson"
)

func main() {
	addr, offsetFile := os.Args[1], os.Args[2]
	sch, err := crypto.SchemeFromName(crypto.SigsOnG1ID)
	if err != nil {
		panic(err)
	}
	priv := sch.KeyGroup.Scalar().Pick(random.New())
	pub := sch.KeyGroup.Point().Mul(priv, nil)
	genesis := time.Now().Unix() - 3000
	info := &chain.Info{PublicKey: pub, ID: "quicknet", Period: 3 * time.Second, Scheme: sch.Name, GenesisTime: genesis, GenesisSeed: bytes.Repeat([]byte{7}, 32)}
	var buf bytes.Buffer
	if err := info.ToJSON(&buf, nil); err != nil {
		panic(err)
	}
	back, err := chain.InfoFromJSON(bytes.NewReader(buf.Bytes()))
	if err != nil {
		panic(err)
	}
	hash := back.HashString()
	fmt.Println(hash, genesis)
	os.Stdout.Sync()

	published := func() uint64 {
		off := int64(0)
		if b, err := os.ReadFile(offsetFile); err == nil {
			off, _ = strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64)
		}
		return uint64((time.Now().Unix()-genesis)/3+1) + uint64(off)
	}

	http.HandleFunc("/"+hash+"/info", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write(buf.Bytes())
	})
	http.HandleFunc("/"+hash+"/public/", func(w http.ResponseWriter, r *http.Request) {
		round, err := strconv.ParseUint(strings.TrimPrefix(r.URL.Path, "/"+hash+"/public/"), 10, 64)
		if err != nil || round == 0 {
			http.Error(w, "bad round", http.StatusBadRequest)
			return
		}
		if round > published() {
			http.Error(w, "Requested future beacon", http.StatusTooEarly)
			return
		}
		sig, err := sch.AuthScheme.Sign(priv, sch.DigestBeacon(&common.Beacon{Round: round}))
		if err != nil {
			panic(err)
		}
		out, _ := json.Marshal(&client.RandomData{Rnd: round, Sig: sig, Random: crypto.RandomnessFromSignature(sig)})
		w.Header().Set("Content-Type", "application/json")
		w.Write(out)
	})
	panic(http.ListenAndServe(addr, nil))
}
