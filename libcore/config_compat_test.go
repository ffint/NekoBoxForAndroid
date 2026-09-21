package libcore

import (
	"encoding/json"
	"testing"
)

func decodeCompatConfig(t *testing.T, input string) map[string]any {
	t.Helper()
	output, err := migrateLegacyConfig(input)
	if err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	if err := json.Unmarshal([]byte(output), &root); err != nil {
		t.Fatal(err)
	}
	return root
}

func TestMigrateLegacyDNS(t *testing.T) {
	root := decodeCompatConfig(t, `{
	  "dns": {
	    "servers": [
	      {"tag":"dns-local","address":"local","detour":"direct"},
	      {"tag":"dns-direct","address":"https://223.5.5.5/dns-query","address_resolver":"dns-local","strategy":"ipv4_only"},
	      {"tag":"dns-remote","address":"https://dns.google/dns-query","address_resolver":"dns-direct","detour":"proxy","strategy":"prefer_ipv4"},
	      {"tag":"dns-block","address":"rcode://success"},
	      {"tag":"dns-fake","address":"fakeip","strategy":"ipv4_only"}
	    ],
	    "rules": [
	      {"domain_suffix":["ads.example"],"server":"dns-block"},
	      {"domain_suffix":["direct.example"],"server":"dns-direct"},
	      {"inbound":["tun-in"],"server":"dns-fake"}
	    ],
	    "final":"dns-remote",
	    "fakeip":{"enabled":true,"inet4_range":"198.18.0.0/15","inet6_range":"fc00::/18"}
	  }
	}`)

	dns := root["dns"].(map[string]any)
	if _, exists := dns["fakeip"]; exists {
		t.Fatal("legacy dns.fakeip was not removed")
	}
	if dns["strategy"] != "prefer_ipv4" {
		t.Fatalf("expected final strategy migration, got %#v", dns["strategy"])
	}

	servers := dns["servers"].([]any)
	if len(servers) != 4 {
		t.Fatalf("expected rcode server removal, got %d servers", len(servers))
	}
	for _, raw := range servers {
		server := raw.(map[string]any)
		if _, exists := server["address"]; exists {
			t.Fatalf("legacy address survived: %#v", server)
		}
		if server["type"] == nil {
			t.Fatalf("server type missing: %#v", server)
		}
	}

	rules := dns["rules"].([]any)
	blockRule := rules[0].(map[string]any)
	if blockRule["action"] != "predefined" || blockRule["rcode"] != "NOERROR" {
		t.Fatalf("rcode rule not migrated: %#v", blockRule)
	}
	directRule := rules[1].(map[string]any)
	if directRule["strategy"] != "ipv4_only" {
		t.Fatalf("DNS strategy not migrated: %#v", directRule)
	}
}

func TestMigrateLegacyInboundFields(t *testing.T) {
	root := decodeCompatConfig(t, `{
	  "inbounds":[{"type":"tun","tag":"tun-in","sniff":true,"sniff_timeout":"1s","domain_strategy":"prefer_ipv4"}],
	  "route":{"rules":[{"inbound":["tun-in"],"outbound":"proxy"}]}
	}`)

	inbound := root["inbounds"].([]any)[0].(map[string]any)
	for _, key := range []string{"sniff", "sniff_timeout", "domain_strategy"} {
		if _, exists := inbound[key]; exists {
			t.Fatalf("legacy inbound field %s survived", key)
		}
	}

	rules := root["route"].(map[string]any)["rules"].([]any)
	if len(rules) != 3 {
		t.Fatalf("expected resolve + sniff + original rule, got %d", len(rules))
	}
	if rules[0].(map[string]any)["action"] != "resolve" {
		t.Fatalf("resolve rule missing: %#v", rules[0])
	}
	if rules[1].(map[string]any)["action"] != "sniff" {
		t.Fatalf("sniff rule missing: %#v", rules[1])
	}
}

func TestMigrateLegacyWireGuardOutbound(t *testing.T) {
	root := decodeCompatConfig(t, `{
	  "outbounds":[
	    {"type":"direct","tag":"direct"},
	    {
	      "type":"wireguard",
	      "tag":"wg-out",
	      "server":"wg.example.com",
	      "server_port":51820,
	      "local_address":["10.0.0.2/32","fd00::2/128"],
	      "private_key":"private",
	      "peer_public_key":"public",
	      "pre_shared_key":"psk",
	      "reserved":"AAEC",
	      "mtu":1408,
	      "detour":"direct"
	    }
	  ]
	}`)

	outbounds := root["outbounds"].([]any)
	if len(outbounds) != 1 || outbounds[0].(map[string]any)["type"] != "direct" {
		t.Fatalf("legacy WireGuard outbound was not removed: %#v", outbounds)
	}
	endpoints := root["endpoints"].([]any)
	if len(endpoints) != 1 {
		t.Fatalf("expected one WireGuard endpoint, got %d", len(endpoints))
	}
	endpoint := endpoints[0].(map[string]any)
	if endpoint["type"] != "wireguard" || endpoint["tag"] != "wg-out" {
		t.Fatalf("unexpected endpoint identity: %#v", endpoint)
	}
	peers := endpoint["peers"].([]any)
	peer := peers[0].(map[string]any)
	if peer["address"] != "wg.example.com" || peer["public_key"] != "public" {
		t.Fatalf("WireGuard peer not migrated: %#v", peer)
	}
}

func TestMigrateLegacyWireGuardRefusesLossyFields(t *testing.T) {
	_, err := migrateLegacyConfig(`{
	  "outbounds":[{"type":"wireguard","tag":"wg-out","server":"1.1.1.1","server_port":51820,"gso":true}]
	}`)
	if err == nil {
		t.Fatal("expected legacy gso migration to fail explicitly")
	}
}
