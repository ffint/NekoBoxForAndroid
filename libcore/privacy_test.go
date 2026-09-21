package libcore

import "testing"

func TestParseWhoamiTXTKeyValue(t *testing.T) {
	ip, asn, country := parseWhoamiTXT([]string{
		"ip=203.0.113.10 asn=AS64500 country=SG",
	})
	if ip != "203.0.113.10" || asn != "AS64500" || country != "SG" {
		t.Fatalf("unexpected result: ip=%q asn=%q country=%q", ip, asn, country)
	}
}

func TestParseWhoamiTXTLooseFields(t *testing.T) {
	ip, asn, country := parseWhoamiTXT([]string{
		"2001:db8::1234 AS64501 JP",
	})
	if ip != "2001:db8::1234" || asn != "AS64501" || country != "JP" {
		t.Fatalf("unexpected result: ip=%q asn=%q country=%q", ip, asn, country)
	}
}
