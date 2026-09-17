# Run only against a qualified reopened local demo; this never submits a run.
# SAMIZDAT_DEMO_TUI_COMMAND must launch the actual tui alias with a local
# ftxui-jolt root and a credential-free loopback server URL. Start vhs from
# a fresh capture output directory; screenshots contain public fixture content.
Output samizdat-local-demo.gif
Output samizdat-local-demo.webm
Set Width 1440
Set Height 1000
Set FontSize 18
Set TypingSpeed 10ms
Hide
Type 'eval "$SAMIZDAT_DEMO_TUI_COMMAND"'
Enter
Sleep 5s
Show
Sleep 8s
Screenshot samizdat-local-demo.png
Ctrl+q
Sleep 1s
