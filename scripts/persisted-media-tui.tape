# Requires the GET-only proxy and copied public fixture from persisted-media.sh.
# No compose box, mutation controls or new inference. Preserve historical media.
# VHS0.11 treats a .png output as a DIRECTORY containing the original frames.
# Independent explicit-rate encoding avoids its slow/default25fps margin path.
Output frames.png
Set Width 1440
Set Height 1000
Set FontSize 20
Set Framerate 8
Set TypingSpeed 10ms
Hide
Type 'eval "$SAMIZDAT_DEMO_TUI_COMMAND"'
Enter
# Installed VHS0.11 supports Wait+Screen@timeout /regexp/. Fail before recording
# a shell error: require an actual connected footer with the retained12turn run.
Wait+Screen@20s /connected.*12.*14.*exhaust/
Show
Sleep 6s
Screenshot tui-runs.png
# The known run/branch was selected by the successful readiness poll. Do not
# reselect it: selection clears fetched conversation until another poll.
Sleep 7s
Screenshot tui-run.png
Right
Enter
Sleep 8s
Screenshot tui-tool-arguments.png
Down
Enter
Sleep 8s
Screenshot tui-tool-result.png
Down
Enter
Sleep 7s
Screenshot tui-turns.png
Down
Enter
Sleep 6s
Screenshot tui-next-result.png
Ctrl+q
Sleep 1s
