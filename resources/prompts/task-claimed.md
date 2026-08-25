You are now working on **{{id}} — {{title}}**.
{% if body %}
{{body}}
{% endif %}{% if contract %}
CONTRACT — what the work must satisfy:
{{contract}}
{% endif %}{% if tests %}
TESTS — what defines delivery:
{{tests}}
{% endif %}
This is your task until you close it. Everything you do from here should serve
it; if you find work that does not, make it another task rather than widening
this one. Close it with `task close` when the contract is met, and say what you
did — then claim the next one.
