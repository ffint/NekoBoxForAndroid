package libcore

import (
	"fmt"
	"strings"

	"libcore/stun"
)

type StunResult struct {
	Text    string
	Success bool
}

func StunTest(server string) *StunResult {
	// note: this library doesn't support stun1.l.google.com:19302
	ret := &StunResult{}
	var text string

	// Classic NAT discovery can still return a useful external endpoint even
	// when the RFC 5780 behavior test is unsupported by the selected server.
	client := stun.NewClient()
	client.SetServerAddr(server)
	nat, host, discoverErr, fakeFullCone := client.Discover()
	if discoverErr != nil {
		text += fmt.Sprintln("Classic discovery error:", discoverErr.Error())
	}

	if fakeFullCone {
		text += fmt.Sprintln("Classic discovery note: the STUN server did not change endpoint; full-cone detection is not reliable for this server.")
	}

	if host != nil {
		text += fmt.Sprintln("NAT Type:", nat)
		text += fmt.Sprintln("External IP Family:", host.Family())
		text += fmt.Sprintln("External IP:", host.IP())
		text += fmt.Sprintln("External Port:", host.Port())
	}

	// RFC 5780 mapping/filtering behavior test. Some public STUN servers do not
	// expose the alternate response IP/port required by this test. Treat that
	// as a partial result instead of making the whole NAT check look broken.
	natBehavior, behaviorErr := client.BehaviorTest()
	if behaviorErr != nil {
		if strings.Contains(strings.ToLower(behaviorErr.Error()), "response ip/port") {
			text += fmt.Sprintln("Behavior test: unavailable (this STUN server does not provide the alternate response IP/port required for RFC 5780).")
		} else {
			text += fmt.Sprintln("Behavior test error:", behaviorErr.Error())
		}
	}

	if natBehavior != nil {
		text += fmt.Sprintln("Mapping Behavior:", natBehavior.MappingType)
		text += fmt.Sprintln("Filtering Behavior:", natBehavior.FilteringType)
		text += fmt.Sprintln("Normal NAT Type:", natBehavior.NormalType())
	}

	ret.Success = host != nil || natBehavior != nil
	if !ret.Success && strings.TrimSpace(text) == "" {
		text = "No usable STUN result"
	}
	ret.Text = strings.TrimRight(text, "\n")
	return ret
}
