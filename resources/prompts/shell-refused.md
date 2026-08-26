Command needs approval: `{{command}}`.
{% if promoted %}
`{{head}}` on its own IS allowed. This was refused for being a COMPOUND
command — it contains {{markers}} — and a compound command is judged as one
whole claim rather than by its first word, because `echo $(rm -rf ~)` must not
be allowed on the strength of `echo`. That rule is not going to be relaxed.

Issue the parts as separate calls. Each is judged on its own, and the parts of
what you just tried are very likely allowed individually.
{% else %}{% if complex %}
This is a COMPOUND command — it contains {{markers}} — so it is judged as one
whole claim rather than by its first word, and `{{head}}` is not allowed on its
own either. Split it up and check the parts.
{% else %}
`{{head}}` is not on the allow list, so it needs a human to grant it — and if
this run has no human watching, it will not be granted. Prefer a tool that does
the same job without the shell: `read_file` and `grep` to look around, `eval`
to run Clojure, including this project's own tests once you have required the
namespace.
{% endif %}{% endif %}