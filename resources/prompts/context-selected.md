Context selected for this task and already loaded — you do not need to load it again:
{% for id in injected %}- {{id}}
{% endfor %}{% if already-present %}Already in your prompt: {{already-present|join:", "}}
{% endif %}
{{bodies}}
