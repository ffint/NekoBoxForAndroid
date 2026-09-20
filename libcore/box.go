package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"net/http"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/conntrack"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var mainInstance *BoxInstance

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		conntrack.Close()
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[platform.Interface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:          instance,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// clear main instance
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	var connectionTracker adapter.ConnectionTracker
	// test i
	if i != nil {
		if i.v2api != nil {
			connectionTracker = i.v2api.StatsService()
		}
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(i.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test direct
	if mainInstance == nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(nil, nil), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test mainInstance
	if mainInstance.v2api != nil {
		connectionTracker = mainInstance.v2api.StatsService()
	}
	return speedtest.UrlTest(boxapi.CreateProxyHttpClient(mainInstance.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
}


type throughputTestResult struct {
	TTFBMillis     int64   `json:"ttfbMillis"`
	Bytes          int64   `json:"bytes"`
	DurationMillis int64   `json:"durationMillis"`
	Mbps           float64 `json:"mbps"`
}

// ThroughputTestJSON performs a bounded HTTP download through the supplied
// sing-box instance. A nil instance is intentionally rejected so Smart Group
// measurements can never silently fall back to the device DIRECT network.
func ThroughputTestJSON(i *BoxInstance, link string, timeout int32, maxBytes int64) (result string, err error) {
	defer device.DeferPanicToError("box.ThroughputTest", func(err_ error) { err = err_ })
	if i == nil || i.Box == nil {
		return "", errors.New("proxy instance required")
	}
	if maxBytes <= 0 {
		maxBytes = 256 * 1024
	}
	if timeout <= 0 {
		timeout = 10000
	}

	var connectionTracker adapter.ConnectionTracker
	if i.v2api != nil {
		connectionTracker = i.v2api.StatsService()
	}
	client := boxapi.CreateProxyHttpClient(i.Box, connectionTracker)
	client.Timeout = time.Duration(timeout) * time.Millisecond
	defer client.CloseIdleConnections()

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Range", fmt.Sprintf("bytes=0-%d", maxBytes-1))
	req.Header.Set("Accept-Encoding", "identity")

	started := time.Now()
	response, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	ttfb := time.Since(started)
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return "", fmt.Errorf("unexpected HTTP status: %s", response.Status)
	}

	bodyStarted := time.Now()
	n, err := io.Copy(io.Discard, io.LimitReader(response.Body, maxBytes))
	if err != nil {
		return "", err
	}
	bodyDuration := time.Since(bodyStarted)
	if n <= 0 {
		return "", errors.New("empty throughput response")
	}
	seconds := bodyDuration.Seconds()
	if seconds <= 0 {
		seconds = 0.001
	}

	payload, err := json.Marshal(throughputTestResult{
		TTFBMillis:     ttfb.Milliseconds(),
		Bytes:          n,
		DurationMillis: time.Since(started).Milliseconds(),
		Mbps:           float64(n*8) / seconds / 1_000_000,
	})
	if err != nil {
		return "", err
	}
	return string(payload), nil
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
