{{label}}

{% for b in branches %}## {{b.branch}}{% if b.goal %} — was proving: {{b.goal}}{% endif %}{% if b.outstanding %}

Outstanding sub-claims (undischarged):
{{b.outstanding}}{% endif %}{% if b.established %}

Established (engine-confirmed):
{{b.established}}{% endif %}{% if b.existential %}

Existential only — the engine confirmed existence, not an instance:
{{b.existential}}{% endif %}{% if b.measured %}

Measured — what a computation produced at the parameters it was run at, not a proof:
{{b.measured}}{% endif %}{% if b.ambiguous %}

Ambiguous — the engine returned no decisive verdict; substantiates nothing:
{{b.ambiguous}}{% endif %}{% if b.drift %}

Thesis drift — the last audit found the evidence establishes "{{b.drift-established}}", {% if b.drift-relaxation %}strictly weaker than the goal{% else %}matching the goal{% endif %} "{{b.drift-goal}}".{% endif %}
{% endfor %}{% if failures %}
## Shared failure log (most recent first)
{{failures}}{% endif %}{% if gate-tally %}
## Gate firings
{{gate-tally}}{% endif %}
