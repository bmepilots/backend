# Exam prep: content, grading and persistence

Updated: 2026-10-13. API contract **5**. This is original student practice material, not an official BME question paper or marking scheme.

## Scope and curriculum

The first subject is **Basics of (Mechanical) Engineering**, ID `basics-of-engineering`, first midterm. There are **38 original exercises**: 8 equivalent-resistance problems, 4 Ohm/divider/power problems, 4 equation-writing problems, 6 superposition problems, 4 optional AC/charge calculations and 12 theory questions. These include new topologies and reasoning tasks, not just copies with changed numbers. Foundation, Developing and Challenge labels describe practice difficulty, not exam predictions.

The reported exam format is three theory questions plus one calculation each on equivalent resistance, writing equations and superposition. Mock exams enforce that composition. AC calculations are extra practice, not an assertion that the reported calculation slots contain AC. Norton and Thevenin are theory-focused, following the user's notes. Theory is currently multiple choice; a formal written theory response is not machine-graded.

The bank includes negative current references, loaded dividers, resistor power, measurement-port changes, a balanced bridge, signed superposition, opposing sources, a clamped node with zero source contribution, and a full five-resistor/two-mesh equation set. The latter asks for five Ohm equations, KCL at both essential nodes and two KVL equations, and explains why the two KCL equations are dependent.

## Source analysis and teaching decisions

The user supplied two PowerPoint decks and six photographs. All 69 slides were extracted from OOXML, including text and embedded equations/illustrations. Embedded media contact sheets were inspected; transparent equations were composited on white for inspection. There were no substantive speaker notes. Full PowerPoint rendering was unavailable on the workstation, so this is text/media analysis rather than a claim of pixel-perfect full-slide rendering. Source attachments and extracted images are not copied into the repository or published by the app.

| Source | Coverage and use |
| --- | --- |
| `electroeng-1.pptx`, 45 slides | Charge, field, potential and current (3–11); resistors/Ohm law and colour codes (12–20); potentiometers and network vocabulary (21–25); series/parallel and worked DC examples (26–29); meters (30–36); Ohm, nodes, loops, KVL/KCL and dividers (37–45). The practice focus is the latter circuit material. |
| `electromeng-2.pptx`, 24 slides | Laws/dividers (2); sources and superposition (3–4); AC waveforms/phasors (5–9); capacitors (10–15); inductors (16–19); RMS (20–21); complex impedance and phase relationships (22–24). |
| `6WhPowyd.jpg` | Reports three theoretical questions and three calculations: total resistance, all equations, superposition. This determines the mock-exam blueprint. |
| `7L7wYgyp.jpg` | Voltage source, R1/R2 in the left path, R3/R5 in the middle and R4 on the right; two essential nodes and two independent meshes. The original equation exercise uses this structure with explicit labels and polarity. |
| `LhND8SA5.jpg` | Adds a current-source/resistor branch. Motivates correct source deactivation and the distinction between an ideal current constraint and a resistor equation. |
| `JNq6hCpC.jpg` | Superposition network with a voltage source clamping the node feeding the output divider. Motivates the zero-contribution exercise; the source arrow and cropped values are not treated as unambiguous numerical specifications. |
| `Lu5I07_b.jpg` | Handwritten Ohm/KCL equations with multiple branches and a current source. Motivates complete equation sets, explicit references, and algebraic equivalence. Crossed-out labels are not reused as authoritative equations. |
| `Rg025ivp.jpg` | Resistance measured at different terminal pairs; also mentions RC high-pass/cutoff. Motivates terminal-change exercises. Some wiring/formula correspondence is ambiguous, so new explicit networks replace a literal transcription. RC filter design is not included as confirmed core exam content. |

Source corrections: the first deck's parallel-resistance text contains an incomplete product expression; the application uses the reciprocal sum. A colour-code multiplier digit is an exponent of ten. Microfarads must not become millifarads during font/text extraction. Conventional current and passive resistor voltage references are used consistently. A voltage-reference arrow in a handwritten image is not silently assumed to define an unmarked source polarity.

Norton/Thevenin are named by the user but not developed in the two supplied decks. The supplementary primary reference is [Agarwal and Lang, MIT OCW 6.002, Lecture 3: Superposition, Thevenin and Norton](https://ocw.mit.edu/courses/6-002-circuits-and-electronics-spring-2007/resources/6002_l3/), especially source deactivation and terminal equivalents. The application's explanations and questions are original, not copied lecture questions.

## Ownership and file map

- `study/StudyCatalog.java`: loads the versioned resource, validates unique exercise/field IDs and answer-key gradeability, exposes a public DTO without answers, explanations or solution steps.
- `src/main/resources/study/engineering-v1.json`: runtime bank, including lessons, diagrams, prompts, field definitions and private answer keys.
- `scripts/generate_study_catalog.py`: deterministic standard-library Python authoring script; produces the committed JSON. Python is not required in the backend image or on the VM.
- `study/StudyGrader.java`: bounded numeric/unit and linear-equation grading, with no execution of user input.
- `study/StudyService.java`: authoritative mode/blueprint validation, grading, private saved results and replay protection.
- `study/StudyController.java`: authenticated HTTP boundary using existing session, ACTIVE-user and CSRF protections.
- Flyway `V11__study_attempts.sql`: durable personal attempts. Schema belongs only to this repository.
- Frontend `src/features/study`: presentation, source switching, answer collection, feedback and private history. It does not grade answers.

## Circuit model and numerical verification

Nodes have a stable ID and drawing coordinates. Parts have IDs, kind, from/to node IDs, SI value and unit. `wire` unites nodes electrically. A voltage source imposes V(from) − V(to) = value; a current source flows from → to. Resistor current references are explicitly stated by each question. All DC sources in this bank are independent and ideal. The SVG uses the same part/node model as the offline calculation, rather than an unrelated picture.

The generator collapses ideal wires and solves a modified nodal system with partial-pivot Gaussian elimination. Every solve checks the original KCL/source equations and total power balance. Resistance is measured using a 1 A test current at the named terminals. Superposition solves all-sources and individual-source circuits separately and asserts that signed current contributions sum to the complete solution. An inactive voltage source remains a zero-volt constraint; an inactive current source contributes no branch current. Power is computed from the combined current, never by summing single-source powers.

Independent hand-calculated values in `StudyGraderTest` check all eight resistance answers and selected superposition/AC answers, including a zero contribution and reversal. These complement, rather than replace, the generator's internal consistency checks. UI diagrams must also be visually reviewed when topology/coordinates change.

## Grading contract

Every field earns zero or one point; partial credit is the number of correct fields. No official pass mark is claimed. Empty, malformed or unsupported answers earn zero with the expected answer and explanation after submission.

Numeric input accepts a signed decimal, scientific notation, one decimal comma, an optional matching unit and SI prefixes p/n/u/µ/μ/m/k/M. Bare values use the displayed base unit. Examples: `20 mA`, `0,02 A`, `2e-2`, `2.2 kohm`, `2.2 kΩ`. Unit names are case-sensitive; `m` alone is not accepted as mA. Wrong dimensions, NaN/infinity and arithmetic expressions are rejected. Tolerance is `max(1e-6 SI units, abs(expected)*0.005)`, stated in the UI. This bank's small charge/energy examples remain well above the absolute tolerance; reconsider per-field tolerances before adding much smaller quantities.

Equation input uses exact case-sensitive variable names, `=`, `+`, `-` (also Unicode minus), `*` (also ×), `/` and parentheses. Decimal constants are allowed; equation scientific notation and implicit multiplication are currently unsupported. Named resistor and independent-source constants are substituted from the exercise, so the given numerical values and their named symbols are both accepted. Expressions must be linear: multiplication requires a constant operand and division a finite nonzero constant divisor. Input length is at most 256 and nested parsing depth at most 32.

The grader subtracts equation sides and compares symbolic coefficient vectors up to a nonzero scale. It accepts rearrangement and reversed signs. It does not merely check an equation at one numerical solution; `0=0`, a wrong variable, nonlinear input or an unrelated true numerical equality cannot earn credit. Each field grades the specified relation, not arbitrary combinations of the complete system or a student's alternative choice of reference directions. The prompt explicitly fixes those references.

## Persistence, privacy and retries

`study_attempts` stores owner UUID, client request UUID, subject/catalog version, PRACTICE/MOCK mode, complete submitted answers, complete result snapshot, points and UTC creation time. The result snapshot includes the original public question, diagram, field labels/units and solution, so future content edits cannot rewrite past feedback. Raw answers are personal study data and are not included in audit events.

A member-row lock and unique `(user_id, request_id)` key serialize overlapping submissions from the same member. Repeating the same UUID and semantically identical JSON returns the stored result without extra points/history/audit events. Reusing it for different content returns 409. This works across browser retries and backend restarts. Invalid payloads do not create attempts. The browser retains the exact pending request after a network failure and locks editing until retry or exit.

History and detail queries always bind the authenticated owner. Another member, including an administrator using these endpoints, cannot request a different member's attempts. There is no leaderboard or admin results view. `STUDY_ATTEMPT_SUBMITTED` records the actor and attempt ID, in addition to existing safe request audit metadata. Existing database backups include this table; no uploaded files or new Docker volumes are involved. Saved attempts are retained indefinitely under existing account/data operations; no automatic study-retention job is introduced.

## API

- `GET /api/v1/study/subjects`: subject list with lessons and public exercises, never private answer keys.
- `POST /api/v1/study/attempts`: `{requestId, subjectId, version, mode, submissions:[{exerciseId, answers:{fieldId:string}}]}`. Returns `{id, earned, possible, exercises:[{exerciseId, earned, possible, fields, steps, question}]}`. Fields return submitted answer, correctness, expected value and explanation.
- `GET /api/v1/study/progress`: authenticated member's first-subject aggregate `{total:{attempts,earned,possible},recent:[...]}`; recent list is capped at 20 and includes UTC `created_at`.
- `GET /api/v1/study/attempts/{id}`: the owner's saved result, otherwise 404.

PRACTICE requires exactly one exercise. MOCK requires six distinct exercises with exactly three Theory, one Resistance, one Equations and one Superposition. The frontend samples each pool without replacement; the server independently checks composition. This is a personal practice tool, not a tamper-proof examination platform. Existing APIs are retained; backend advertises `1,2,3,4,5`, frontend requires `5`.

## Extending and operating

1. Add/edit original content in the generator, define all units/references and write a real solution. Preserve stable exercise IDs for the same learning task; use a new ID for a different task.
2. Increment the catalog version on any prompt, grading, diagram or answer-key change. New submissions using an old version receive 409; an exact replay of an already stored request still succeeds.
3. Run `python scripts/generate_study_catalog.py`; review the generated JSON diff and inspect circuits visually. Do not hand-edit the generated JSON alone.
4. Add independent numerical expectations and meaningful grader/HTTP tests for new behavior. Run the isolated Docker MariaDB test suite and frontend checks. Record actual evidence in STATUS.
5. Add future subjects by generalizing `StudyCatalog` to load multiple resource files, lookup by subject in the service and subject-filtered progress. The frontend's subject selector and types are ready for this, but the initial backend intentionally loads only the first subject. Do not claim arbitrary subjects can already be authored from the admin UI.

Known boundaries: no general SPICE simulator, component drag-and-drop editor, AI grading, free-text theory grading, timer/pass certificate, public competition, admin question editor or attachments distribution. Draft answers stay in React memory and are lost on page reload/navigation; submitted attempts persist. No new infrastructure or Gmail behavior is required. Never turn on real email to verify this feature.
