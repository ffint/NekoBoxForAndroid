package libcore

import (
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// migrateLegacyConfig upgrades configuration fragments that were accepted by
// the NekoBox-compatible sing-box 1.12 core but were removed by sing-box
// 1.13/1.14. The migration is intentionally conservative: already-modern
// fields are left untouched, unknown fields are preserved, and parse errors
// are returned instead of silently dropping user configuration.
func migrateLegacyConfig(config string) (string, error) {
	var root map[string]any
	if err := json.Unmarshal([]byte(config), &root); err != nil {
		return "", fmt.Errorf("decode compatibility config: %w", err)
	}

	if err := migrateLegacyDNS(root); err != nil {
		return "", err
	}
	if err := migrateLegacyWireGuard(root); err != nil {
		return "", err
	}
	migrateLegacyInboundFields(root)

	data, err := json.Marshal(root)
	if err != nil {
		return "", fmt.Errorf("encode compatibility config: %w", err)
	}
	return string(data), nil
}

func migrateLegacyDNS(root map[string]any) error {
	dnsObject, ok := asStringMap(root["dns"])
	if !ok {
		return nil
	}

	fakeIPOptions, _ := asStringMap(dnsObject["fakeip"])
	legacyStrategies := map[string]string{}
	legacyRCodes := map[string]string{}

	rawServers, _ := dnsObject["servers"].([]any)
	if len(rawServers) > 0 {
		modernServers := make([]any, 0, len(rawServers))
		for _, rawServer := range rawServers {
			server, ok := asStringMap(rawServer)
			if !ok {
				modernServers = append(modernServers, rawServer)
				continue
			}

			// Modern DNS server format already has an explicit type.
			if serverType, _ := server["type"].(string); serverType != "" {
				modernServers = append(modernServers, server)
				continue
			}

			address, _ := server["address"].(string)
			if address == "" {
				// Let sing-box report the malformed server rather than
				// inventing semantics for it.
				modernServers = append(modernServers, server)
				continue
			}

			tag, _ := server["tag"].(string)
			if strategy, _ := server["strategy"].(string); strategy != "" && tag != "" {
				legacyStrategies[tag] = strategy
			}

			modern, rcode, err := convertLegacyDNSServer(server, fakeIPOptions)
			if err != nil {
				return fmt.Errorf("migrate DNS server %q: %w", tag, err)
			}
			if rcode != "" {
				if tag == "" {
					return fmt.Errorf("legacy rcode DNS server has no tag")
				}
				legacyRCodes[tag] = rcode
				continue
			}
			modernServers = append(modernServers, modern)
		}
		dnsObject["servers"] = modernServers
	}

	// Legacy fakeip options are carried by the modern fakeip server itself.
	delete(dnsObject, "fakeip")

	if rawRules, ok := dnsObject["rules"].([]any); ok {
		for _, rawRule := range rawRules {
			if rule, ok := asStringMap(rawRule); ok {
				rewriteLegacyDNSRule(rule, legacyStrategies, legacyRCodes)
			}
		}
	}

	if finalTag, _ := dnsObject["final"].(string); finalTag != "" {
		if rcode := legacyRCodes[finalTag]; rcode != "" {
			delete(dnsObject, "final")
			catchAll := map[string]any{
				"action": "predefined",
				"rcode":  rcode,
			}
			rules, _ := dnsObject["rules"].([]any)
			dnsObject["rules"] = append(rules, catchAll)
		} else if strategy := legacyStrategies[finalTag]; strategy != "" {
			if _, exists := dnsObject["strategy"]; !exists {
				dnsObject["strategy"] = strategy
			}
		}
	}

	root["dns"] = dnsObject
	return nil
}

func convertLegacyDNSServer(server map[string]any, fakeIPOptions map[string]any) (map[string]any, string, error) {
	address, _ := server["address"].(string)
	address = strings.TrimSpace(address)
	if address == "" {
		return nil, "", fmt.Errorf("empty address")
	}

	// Copy all non-legacy fields first so custom fields survive migration.
	modern := make(map[string]any, len(server)+4)
	for key, value := range server {
		switch key {
		case "address", "address_resolver", "address_strategy", "address_fallback_delay", "strategy":
			continue
		default:
			modern[key] = value
		}
	}

	if fallback, exists := server["address_fallback_delay"]; exists {
		modern["fallback_delay"] = fallback
	}

	if resolver, _ := server["address_resolver"].(string); resolver != "" {
		if resolverStrategy, _ := server["address_strategy"].(string); resolverStrategy != "" {
			modern["domain_resolver"] = map[string]any{
				"server":   resolver,
				"strategy": resolverStrategy,
			}
		} else {
			modern["domain_resolver"] = resolver
		}
	}

	if address == "local" {
		modern["type"] = "local"
		return modern, "", nil
	}
	if address == "fakeip" {
		modern["type"] = "fakeip"
		if value, exists := fakeIPOptions["inet4_range"]; exists {
			modern["inet4_range"] = value
		}
		if value, exists := fakeIPOptions["inet6_range"]; exists {
			modern["inet6_range"] = value
		}
		return modern, "", nil
	}

	parsed, err := url.Parse(address)
	if err != nil {
		return nil, "", err
	}

	scheme := strings.ToLower(parsed.Scheme)
	if scheme == "" {
		modern["type"] = "udp"
		host, port, err := splitHostPort(address, 53)
		if err != nil {
			return nil, "", err
		}
		modern["server"] = host
		if port != 53 {
			modern["server_port"] = port
		}
		return modern, "", nil
	}

	switch scheme {
	case "rcode":
		return modern, normalizeRCode(parsed.Host), nil
	case "dhcp":
		modern["type"] = "dhcp"
		if parsed.Host != "" && parsed.Host != "auto" {
			modern["interface"] = parsed.Host
		}
		return modern, "", nil
	case "tcp", "tls", "quic", "https", "h3":
		modern["type"] = scheme
	default:
		return nil, "", fmt.Errorf("unsupported DNS scheme %q", scheme)
	}

	defaultPort := 53
	switch scheme {
	case "tls", "quic":
		defaultPort = 853
	case "https", "h3":
		defaultPort = 443
	}

	host, port, err := splitHostPort(parsed.Host, defaultPort)
	if err != nil {
		return nil, "", err
	}
	modern["server"] = host
	if port != defaultPort {
		modern["server_port"] = port
	}
	if (scheme == "https" || scheme == "h3") && parsed.Path != "" && parsed.Path != "/dns-query" {
		modern["path"] = parsed.Path
	}
	return modern, "", nil
}

func rewriteLegacyDNSRule(rule map[string]any, strategies, rcodes map[string]string) {
	if nested, ok := rule["rules"].([]any); ok {
		for _, rawNested := range nested {
			if child, ok := asStringMap(rawNested); ok {
				rewriteLegacyDNSRule(child, strategies, rcodes)
			}
		}
	}

	server, _ := rule["server"].(string)
	if server == "" {
		return
	}
	if rcode := rcodes[server]; rcode != "" {
		delete(rule, "server")
		delete(rule, "strategy")
		rule["action"] = "predefined"
		rule["rcode"] = rcode
		return
	}
	if strategy := strategies[server]; strategy != "" {
		if _, exists := rule["strategy"]; !exists {
			rule["strategy"] = strategy
		}
	}
}

func migrateLegacyWireGuard(root map[string]any) error {
	rawOutbounds, _ := root["outbounds"].([]any)
	if len(rawOutbounds) == 0 {
		return nil
	}

	endpoints, _ := root["endpoints"].([]any)
	modernOutbounds := make([]any, 0, len(rawOutbounds))
	for _, rawOutbound := range rawOutbounds {
		outbound, ok := asStringMap(rawOutbound)
		if !ok || outbound["type"] != "wireguard" {
			modernOutbounds = append(modernOutbounds, rawOutbound)
			continue
		}

		if gso, _ := outbound["gso"].(bool); gso {
			return fmt.Errorf("migrate WireGuard outbound: legacy gso=true has no 1.14 endpoint equivalent")
		}
		if network := outbound["network"]; network != nil {
			switch value := network.(type) {
			case string:
				if value != "" {
					return fmt.Errorf("migrate WireGuard outbound: legacy network=%q cannot be preserved safely", value)
				}
			case []any:
				if len(value) > 0 {
					return fmt.Errorf("migrate WireGuard outbound: legacy network restriction cannot be preserved safely")
				}
			}
		}

		endpoint := map[string]any{"type": "wireguard"}
		copyFields(endpoint, outbound,
			"tag",
			"detour",
			"bind_interface",
			"inet4_bind_address",
			"inet6_bind_address",
			"protect_path",
			"routing_mark",
			"reuse_addr",
			"connect_timeout",
			"tcp_fast_open",
			"tcp_multi_path",
			"udp_fragment",
			"domain_strategy",
			"fallback_delay",
			"private_key",
			"workers",
			"mtu",
		)
		if value, exists := outbound["system_interface"]; exists {
			endpoint["system"] = value
		}
		if value, exists := outbound["interface_name"]; exists {
			endpoint["name"] = value
		}
		if value, exists := outbound["local_address"]; exists {
			endpoint["address"] = value
		}

		peers, err := migrateLegacyWireGuardPeers(outbound)
		if err != nil {
			return err
		}
		endpoint["peers"] = peers
		endpoints = append(endpoints, endpoint)
	}

	root["outbounds"] = modernOutbounds
	if len(endpoints) > 0 {
		root["endpoints"] = endpoints
	}
	return nil
}

func migrateLegacyWireGuardPeers(outbound map[string]any) ([]any, error) {
	if rawPeers, ok := outbound["peers"].([]any); ok && len(rawPeers) > 0 {
		peers := make([]any, 0, len(rawPeers))
		for _, rawPeer := range rawPeers {
			peer, ok := asStringMap(rawPeer)
			if !ok {
				return nil, fmt.Errorf("migrate WireGuard outbound: invalid peer")
			}
			modernPeer := map[string]any{}
			copyFields(modernPeer, peer, "public_key", "pre_shared_key", "allowed_ips", "reserved")
			if value, exists := peer["server"]; exists {
				modernPeer["address"] = value
			}
			if value, exists := peer["server_port"]; exists {
				modernPeer["port"] = value
			}
			peers = append(peers, modernPeer)
		}
		return peers, nil
	}

	server, _ := outbound["server"].(string)
	if server == "" {
		return nil, fmt.Errorf("migrate WireGuard outbound: missing server")
	}
	peer := map[string]any{
		"address":     server,
		"allowed_ips": []any{"0.0.0.0/0", "::/0"},
	}
	if value, exists := outbound["server_port"]; exists {
		peer["port"] = value
	}
	copyFields(peer, outbound, "peer_public_key", "pre_shared_key", "reserved")
	if value, exists := peer["peer_public_key"]; exists {
		peer["public_key"] = value
		delete(peer, "peer_public_key")
	}
	return []any{peer}, nil
}

func copyFields(destination map[string]any, source map[string]any, keys ...string) {
	for _, key := range keys {
		if value, exists := source[key]; exists && value != nil {
			destination[key] = value
		}
	}
}

func migrateLegacyInboundFields(root map[string]any) {
	rawInbounds, _ := root["inbounds"].([]any)
	if len(rawInbounds) == 0 {
		return
	}

	route, ok := asStringMap(root["route"])
	if !ok {
		route = map[string]any{}
	}
	existingRules, _ := route["rules"].([]any)
	prefixRules := make([]any, 0)

	for index, rawInbound := range rawInbounds {
		inbound, ok := asStringMap(rawInbound)
		if !ok {
			continue
		}

		tag, _ := inbound["tag"].(string)
		if tag == "" {
			tag = fmt.Sprintf("__compat_inbound_%d", index)
			inbound["tag"] = tag
		}

		domainStrategy, _ := inbound["domain_strategy"].(string)
		if domainStrategy != "" {
			prefixRules = append(prefixRules, map[string]any{
				"inbound":  []any{tag},
				"action":   "resolve",
				"strategy": domainStrategy,
			})
		}

		sniff, _ := inbound["sniff"].(bool)
		if sniff {
			rule := map[string]any{
				"inbound": []any{tag},
				"action":  "sniff",
			}
			if timeout, exists := inbound["sniff_timeout"]; exists {
				rule["timeout"] = timeout
			}
			prefixRules = append(prefixRules, rule)
		}

		if disableUnmapping, _ := inbound["udp_disable_domain_unmapping"].(bool); disableUnmapping {
			prefixRules = append(prefixRules, map[string]any{
				"inbound":                      []any{tag},
				"action":                       "route-options",
				"udp_disable_domain_unmapping": true,
			})
		}

		delete(inbound, "sniff")
		delete(inbound, "sniff_override_destination")
		delete(inbound, "sniff_timeout")
		delete(inbound, "domain_strategy")
		delete(inbound, "udp_disable_domain_unmapping")
	}

	if len(prefixRules) > 0 {
		route["rules"] = append(prefixRules, existingRules...)
		root["route"] = route
	}
}

func splitHostPort(value string, defaultPort int) (string, int, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", 0, fmt.Errorf("empty server")
	}

	// URL.Host keeps IPv6 brackets, which net.SplitHostPort handles correctly.
	if host, portText, err := net.SplitHostPort(value); err == nil {
		port, err := strconv.Atoi(portText)
		if err != nil || port < 1 || port > 65535 {
			return "", 0, fmt.Errorf("invalid port %q", portText)
		}
		return strings.Trim(host, "[]"), port, nil
	}

	// Bare IPv6 address without a port.
	if strings.Count(value, ":") > 1 {
		return strings.Trim(value, "[]"), defaultPort, nil
	}

	// host:port without brackets is handled above. What remains is a plain
	// host/IP using the protocol default port.
	return value, defaultPort, nil
}

func normalizeRCode(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "success", "noerror":
		return "NOERROR"
	case "format_error", "formerr":
		return "FORMERR"
	case "server_failure", "servfail":
		return "SERVFAIL"
	case "name_error", "nxdomain":
		return "NXDOMAIN"
	case "not_implemented", "notimp":
		return "NOTIMP"
	case "refused":
		return "REFUSED"
	default:
		return strings.ToUpper(value)
	}
}

func asStringMap(value any) (map[string]any, bool) {
	switch typed := value.(type) {
	case map[string]any:
		return typed, true
	default:
		return nil, false
	}
}
