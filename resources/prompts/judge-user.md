## The agent's rules

{{rules}}

## Evidence (deterministic facts about the run)

{{evidence}}
{% if diff %}
## Diff of what this run changed

```diff
{{diff}}
```
{% endif %}
## Transcript

{{transcript}}

## The answer it wants to ship

{{answer}}

Is this task complete and correct? {{preamble}}
