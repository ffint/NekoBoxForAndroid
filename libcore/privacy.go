package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
)

type privacyProbeResult struct {
	IPv4               string `json:"ipv4"`
	IPv4Colo           string `json:"ipv4Colo"`
	IPv4Error          string `json:"ipv4Error,omitempty"`
	IPv6               string `json:"ipv6"`
	IPv6Colo           string `json:"ipv6Colo"`
	IPv6Error          string `json:"ipv6Error,omitempty"`
	DNSResolverIP      string `json:"dnsResolverIp"`
	DNSResolverASN     string `json:"dnsResolverAsn"`
	DNSResolverCountry string `json:"dnsResolverCountry"`
	DNSRaw             string `json:"dnsRaw"`
	DNSError           string `json:"dnsError,omitempty"`
}

// PrivacyProbeJSON tests the currently running sing-box instance. HTTP probes
// use fixed Cloudflare IPv4/IPv6 addresses through the active proxy path, so
// the result is not affected by the phone's local DNS. The DNS probe is sent
// through sing-box's DNS router to Cloudflare's documented whoami endpoint.
func PrivacyProbeJSON(timeoutMillis int32) (string, error) {
	if mainInstance == nil || mainInstance.Box == nil {
		return "", errors.New("core not started")
	}
	if timeoutMillis <= 0 {
		timeoutMillis = 8000
	}
	timeout := time.Duration(timeoutMillis) * time.Millisecond

	result := privacyProbeResult{}

	if ip, colo, err := privacyHTTPTrace("https://1.1.1.1/cdn-cgi/trace", timeout); err != nil {
		result.IPv4Error = err.Error()
	} else {
		result.IPv4 = ip
		result.IPv4Colo = colo
	}

	if ip, colo, err := privacyHTTPTrace("https://[2606:4700:4700::1111]/cdn-cgi/trace", timeout); err != nil {
		result.IPv6Error = err.Error()
	} else {
		result.IPv6 = ip
		result.IPv6Colo = colo
	}

	if mainInstance.dnsRouter == nil {
		result.DNSError = "DNS router unavailable"
	} else {
		ip, asn, country, raw, err := privacyDNSWhoami(timeout)
		if err != nil {
			result.DNSError = err.Error()
		} else {
			result.DNSResolverIP = ip
			result.DNSResolverASN = asn
			result.DNSResolverCountry = country
			result.DNSRaw = raw
		}
	}

	payload, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	return string(payload), nil
}

func privacyHTTPTrace(link string, timeout time.Duration) (string, string, error) {
	var tracker adapter.ConnectionTracker
	if mainInstance.v2api != nil {
		tracker = mainInstance.v2api.StatsService()
	}
	client := boxapi.CreateProxyHttpClient(mainInstance.Box, tracker)
	client.Timeout = timeout
	defer client.CloseIdleConnections()

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return "", "", err
	}
	request.Header.Set("User-Agent", "NekoBox-Enhanced-Privacy-Test")

	response, err := client.Do(request)
	if err != nil {
		return "", "", err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return "", "", fmt.Errorf("HTTP %s", response.Status)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 16*1024))
	if err != nil {
		return "", "", err
	}

	values := map[string]string{}
	for _, line := range strings.Split(string(body), "\n") {
		key, value, found := strings.Cut(strings.TrimSpace(line), "=")
		if found && key != "" {
			values[key] = value
		}
	}
	ip := strings.TrimSpace(values["ip"])
	if net.ParseIP(ip) == nil {
		return "", "", fmt.Errorf("invalid trace IP %q", ip)
	}
	return ip, strings.TrimSpace(values["colo"]), nil
}

func privacyDNSWhoami(timeout time.Duration) (string, string, string, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	message := new(dns.Msg)
	message.SetQuestion(dns.Fqdn("whoami.cloudflare.net"), dns.TypeTXT)
	response, err := mainInstance.dnsRouter.Exchange(
		ctx,
		message,
		adapter.DNSQueryOptions{
			DisableCache: true,
			Timeout:      timeout,
		},
	)
	if err != nil {
		return "", "", "", "", err
	}

	var records []string
	for _, answer := range response.Answer {
		if txt, ok := answer.(*dns.TXT); ok {
			records = append(records, txt.Txt...)
		}
	}
	if len(records) == 0 {
		return "", "", "", "", errors.New("empty whoami DNS response")
	}
	raw := strings.Join(records, " ")
	ip, asn, country := parseWhoamiTXT(records)
	if ip == "" {
		return "", "", "", raw, fmt.Errorf("unrecognized whoami DNS response: %s", raw)
	}
	return ip, asn, country, raw, nil
}

var asnPattern = regexp.MustCompile(`(?i)^AS?[0-9]+$`)

func parseWhoamiTXT(records []string) (ip string, asn string, country string) {
	for _, record := range records {
		fields := strings.FieldsFunc(record, func(r rune) bool {
			switch r {
			case ' ', '\t', ',', ';', '|':
				return true
			default:
				return false
			}
		})
		for _, field := range fields {
			field = strings.Trim(strings.TrimSpace(field), "\"'")
			if field == "" {
				continue
			}
			key, value, found := strings.Cut(field, "=")
			if found {
				switch strings.ToLower(strings.TrimSpace(key)) {
				case "ip", "address":
					if parsed := net.ParseIP(strings.TrimSpace(value)); parsed != nil {
						ip = parsed.String()
					}
				case "asn":
					asn = strings.TrimSpace(value)
				case "country", "cc":
					country = strings.ToUpper(strings.TrimSpace(value))
				}
				continue
			}

			if ip == "" {
				if parsed := net.ParseIP(field); parsed != nil {
					ip = parsed.String()
					continue
				}
			}
			if asn == "" && asnPattern.MatchString(field) {
				asn = strings.ToUpper(field)
				continue
			}
			if country == "" && len(field) == 2 {
				country = strings.ToUpper(field)
			}
		}
	}
	return
}
