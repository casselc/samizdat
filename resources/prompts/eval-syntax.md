{% if syntax %}That is not loadable Clojure, so nothing was evaluated: {{syntax}}
Fix it and send the form again — the harness closes a dropped trailing delimiter for you, but it will not guess at one in the middle.{% endif %}
