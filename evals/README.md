# Evals for the Claude Code plugin

These check that the `trace-form` skill loads on natural phrasing, stays out of unrelated questions,
and leads to the right answer. They run the real skill and the real server over `sample-forms`.

Each run makes real model calls, against your plan's usage or your API bill, so nothing runs them
automatically. Run them when the skill or its description changes, and compare with the baseline
below.

## Running them

You need Linux or WSL, Claude Code 2.1.269 or later, `jq`, and a JDK 25+ on `PATH`. Native Windows
hasn't been tried.

```bash
./gradlew :server:installDist
bash evals/run.sh --model sonnet
```

Options are passed on to `claude plugin eval`, such as `--case 'empty-trigger'`, `--runs 1`, or
`--max-cost-usd 2`, which replaces the script's default ceiling of 10. Results go to
`build/evals/<timestamp>/`: `report.html` to read and `result.json` to compare. The exit code is 1
if any case scores below 1.0, and `--threshold` changes that.

Use `run.sh` rather than running `claude plugin eval` on the plugin directly. An eval run has no
`forms_dir` and doesn't get your environment, so the server would answer every tool with "no forms
directory". The script evaluates a temporary copy of the plugin whose `.mcp.json` names `sample-forms`
and your local server build, and changes nothing else. The suite lives here rather than in the
plugin so that it doesn't ship to users, and changing it doesn't need a plugin version bump.

## Cases

| Case | Prompt | What it checks |
|---|---|---|
| `value-source` | Where does ORDERS.CUSTOMER_NAME get its value? | The skill loads, `fetch_module` comes before `get_block`, and the answer names the column and the LOV |
| `empty-trigger` | The Select button on the picker form's toolbar has an empty trigger. What does pressing it do? | The skill loads, the parent is fetched before `resolve: true`, and the answer reaches `Do_Key` and `KEY-HELP` |
| `modal-return` | How does the picker modal return the chosen value to the form that opened it? | The skill loads, the window is checked for modality, and the answer names the global and the calling item |
| `plain-plsql` | What's the difference between a PL/SQL function and a procedure? | The skill and the server stay out |
| `react-modal` | How does a modal dialog in a React form return the selected value to the form that opened it? | The skill and the server stay out, despite the shared words |
| `oracle-apex` | In an Oracle APEX form, how does a modal dialog page return the selected value to an item on the page that opened it? | The skill and the server stay out, in another Oracle product |

Keep at least one grader per case on the answer itself. A grader on the tool call alone passes even
when the tool returned an error.

## Baseline

Plugin 1.7.1, Claude Code 2.1.319, three runs per case. Each cell is the passes out of the runs.

| Case | Grader | Haiku 5.5 | Sonnet 5.5 |
|---|---|---|---|
| `value-source` | `skill-fired` | 3/3 | 3/3 |
| | `fetch-before-block` | 3/3 | 3/3 |
| | `names-the-column` | 2/3 | 3/3 |
| | `names-the-lov` | 3/3 | 3/3 |
| `empty-trigger` | `skill-fired` | 3/3 | 3/3 |
| | `fetch-parent-then-resolve` | 1/3 | 3/3 |
| | `finds-the-inherited-body` | 3/3 | 3/3 |
| | `follows-it-to-key-help` | 3/3 | 3/3 |
| `modal-return` | `skill-fired` | 3/3 | 3/3 |
| | `checks-the-window` | 3/3 | 2/3 |
| | `names-the-global` | 3/3 | 3/3 |
| | `names-the-caller` | 3/3 | 3/3 |
| `plain-plsql` | both | 3/3 | 3/3 |
| `react-modal` | both | 3/3 | 3/3 |
| `oracle-apex` | both | 3/3 | 3/3 |
| **Overall score** | | **0.96** | **0.99** |
| Estimated cost at list price | | $0.10 | $1.51 |

Haiku's two misses didn't come from a wrong trace. Two `fetch-parent-then-resolve` runs tried
`resolve: true` before fetching `TOOLBAR`, which costs one extra call. The `names-the-column` miss was
a 2-1 judge vote on an answer that named the column but led with the LOV.

Three runs is a small sample, so a one-run change isn't a signal on its own.

Before 1.7.1, the description didn't say to load the skill before calling the tools. On the first five
cases, that version scored 0.75 on Haiku, which loaded the skill in 1 of 9 runs, and 0.98 on Sonnet,
which loaded it in 9 of 9.
