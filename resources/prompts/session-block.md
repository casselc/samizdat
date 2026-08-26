## This session so far ({{turns}} turns){% if fitness %} — fitness {{fitness}}/turn{% endif %}
{% if tools %}Tools: {{tools}}
{% endif %}{% if signals %}Call mechanics: {{signals}}
{% endif %}{% if verify %}Ship verification: {{verify}}
{% endif %}{% if gates %}Gates: {{gates}}
{% endif %}{% if experiments %}
### Changes you have made, and what happened
{{experiments}}

{% if unsettled %}**{{unsettled}} change(s) above measured worse or unchanged and you have not acted on them.** Do that first, before anything else: revert them, or say why you are keeping one. A modification the evidence says is not helping, left in place because nobody got back to it, is exactly what this measurement exists to prevent — and the next supervisor inherits it with no sign it was ever questioned.

{% endif %}A change with verdict `better` earned its place — say so and leave it. `worse` means REVERT it; that is not a failure, it is the experiment working. `unchanged` means the change was not the fix, so revert it too rather than leaving a change nobody can justify. `too early` means wait — do not stack another change on an unmeasured one.
{% endif %}{% if findings %}
### Worth your attention
{{findings}}
{% endif %}