package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"libcore/procfs"
	"log"
	"net/netip"
	"strings"
	"syscall"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
)

var boxPlatformInterfaceInstance adapter.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct{}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) error {
	// Main process calls the protect socket exposed by the VPN service.
	if !isBgProcess {
		_ = sendFdToProtect(fd, "protect_path")
		return nil
	}
	return intfBox.AutoDetectInterfaceControl(int32(fd))
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) OpenInterface(
	options *tun.Options,
	platformOptions option.TunPlatformOptions,
) (tun.Tun, error) {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, errors.New("android: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, errors.New("android: unsupported android_user option")
	}
	a, _ := json.Marshal(options)
	b, _ := json.Marshal(platformOptions)
	tunFd, err := intfBox.OpenTun(string(a), string(b))
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup: %v", err)
	}
	options.FileDescriptor = int(tunFd)
	return tun.New(*options)
}

func (w *boxPlatformInterfaceWrapper) ProcessPlatformOptions(options option.TunPlatformOptions) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	return &interfaceMonitorStub{}
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, errors.New("android platform network interfaces are not provided by NekoBox")
}

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

func (w *boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState(ctx context.Context) adapter.WIFIState {
	parts := strings.SplitN(intfBox.WIFIState(), ",", 2)
	state := adapter.WIFIState{}
	if len(parts) > 0 {
		state.SSID = parts[0]
	}
	if len(parts) > 1 {
		state.BSSID = parts[1]
	}
	return state
}

func (w *boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(
	request *adapter.FindConnectionOwnerRequest,
) (*adapter.ConnectionOwner, error) {
	var uid int32
	if useProcfs {
		sourceIP, err := netip.ParseAddr(request.SourceAddress)
		if err != nil {
			return nil, fmt.Errorf("parse source address: %w", err)
		}
		destinationIP, err := netip.ParseAddr(request.DestinationAddress)
		if err != nil {
			return nil, fmt.Errorf("parse destination address: %w", err)
		}
		source := netip.AddrPortFrom(sourceIP, uint16(request.SourcePort))
		destination := netip.AddrPortFrom(destinationIP, uint16(request.DestinationPort))

		var network string
		switch request.IpProtocol {
		case syscall.IPPROTO_TCP:
			network = N.NetworkTCP
		case syscall.IPPROTO_UDP:
			network = N.NetworkUDP
		default:
			return nil, fmt.Errorf("unknown IP protocol: %d", request.IpProtocol)
		}
		uid = procfs.ResolveSocketByProcSearch(network, source, destination)
		if uid == -1 {
			return nil, errors.New("procfs: connection owner not found")
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(
			request.IpProtocol,
			request.SourceAddress,
			request.SourcePort,
			request.DestinationAddress,
			request.DestinationPort,
		)
		if err != nil {
			return nil, err
		}
	}

	packageName, _ := intfBox.PackageNameByUid(uid)
	owner := &adapter.ConnectionOwner{UserId: uid}
	if packageName != "" {
		owner.AndroidPackageNames = []string{packageName}
	}
	return owner, nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNotification() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *adapter.Notification) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CancelNotification(identifier string, typeID int32) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNeighborResolver() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) StartNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return errors.New("android neighbor monitor is not implemented")
}

func (w *boxPlatformInterfaceWrapper) CloseNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformShell() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CheckPlatformShell() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) OpenShellSession(
	user *adapter.PlatformUser,
	command string,
	env []string,
	term string,
	rows int32,
	cols int32,
) (adapter.ShellSession, error) {
	return nil, errors.New("android platform shell is not implemented")
}

func (w *boxPlatformInterfaceWrapper) LookupUser(username string) (*adapter.PlatformUser, error) {
	return nil, errors.New("android user lookup is not implemented")
}

func (w *boxPlatformInterfaceWrapper) LookupSFTPServer() (string, error) {
	return "", errors.New("android SFTP lookup is not implemented")
}

func (w *boxPlatformInterfaceWrapper) ReadSystemSSHHostKey() ([]byte, error) {
	return nil, errors.New("android SSH host key is not implemented")
}

func (w *boxPlatformInterfaceWrapper) TailscaleHostname() string {
	return ""
}

func (w *boxPlatformInterfaceWrapper) UsePlatformBridge() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CreateBridge(options adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, errors.New("android platform bridge is not implemented")
}

// io.Writer

var disableSingBoxLog = false

func (w *boxPlatformInterfaceWrapper) Write(p []byte) (n int, err error) {
	if !disableSingBoxLog {
		log.Print(string(p))
	}
	return len(p), nil
}

// Logging

type boxPlatformLogWriterWrapper struct{}

var boxPlatformLogWriter sblog.PlatformWriter = &boxPlatformLogWriterWrapper{}

func (w *boxPlatformLogWriterWrapper) DisableColors() bool { return true }

func (w *boxPlatformLogWriterWrapper) WriteMessage(level uint8, message string) {
	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	_, _ = neko_log.LogWriter.Write([]byte(message))
}
