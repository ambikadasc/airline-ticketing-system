# Load test: how it works and how to extend it

The commands, the scenarios and the measured figures are in the main
[README](../README.md#load-test). This page is the detail: what the files are, where the results
go, how to read the dashboard, and how to add a scenario.

## Files
| File | Role |
| --- | --- |
| `docker-compose.load.yml` | Overrides for the throwaway Compose project `airline-load`: same image, app on `127.0.0.1:8081`, database not published, pool metrics exposed, a `jmeter` service (`alpine/jmeter`, started only by `run`) |
| `seed.sql` | Pure SQL seed (`psql -v schedules=… -v fill=…`): schedules on every route, one flight per schedule per bookable day, `fill` of each flight's seats pre-booked in two-seat bookings, every tenth booking cancelled; references in the application's alphabet; `available_seats` maintained; ends with `ANALYZE` and `CHECKPOINT` |
| `airline-load.jmx` | The JMeter plan: seven thread groups (search, lookup, cancel, booking, booking-hot, booking-race, guess), each sized by a `-J` property so `0` disables it; extracts `$.code` and `$.status` from every response |
| `../scripts/load-test.sh` | Fresh stack → seed → JMeter → summary → `down -v`. Modes: default, `throttle`, and `AIRLINE_SEAT_HOLD_ENABLED=true` |

## Results
Each mode writes to its own folder, so figures never overwrite each other:
`results/` (default), `results/seat-hold/`, `results/throttle/`. All git-ignored. In each:

- `summary.txt`: the mode, the data set, then per scenario requests, req/s, p50/p90/p95/p99/max
  (elapsed ms) and the responses by status and code with percentages (`201 CONFIRMED x15790
  (73.5%)`, `409 SEAT_UNAVAILABLE x5854 (26.5%)`), the pool metrics, the race verdict and the
  inventory invariant.
- `outcomes.html`: the same as pies, one per scenario, with the data set on top and a
  plain-language note on what each scenario simulates and what a good result looks like.
- `report/index.html`: JMeter's dashboard. **Dashboard** has the pass/fail pie and the
  Statistics table (throughput, p90/p95/p99, Error %); **Charts** has Over Time (response
  times, active threads), Throughput (**Codes Per Second**: HTTP statuses across the run) and
  Response Times (percentiles, distribution). A request fails there only on a 5xx: a 409 (seat
  taken) or 404 is a correct answer, so the pie stays green unless the system itself failed, and
  the Errors table names any 5xx by code (`503/RETRY_LATER`).
- `run.jtl`: one CSV line per request; the last two columns are the error code and the booking
  status from the body.

## Why the throttle test is a separate run
Every JMeter thread leaves the container with the same IP address, and the lookup throttle is per
client address: guessers running beside the steady scenarios would throttle the legitimate
lookups and cancels too. And a guesser refused tens of thousands of times in 20 s is a success
for the system but would drown every other percentage. `bash scripts/load-test.sh throttle`
runs the guessers alone against a one-schedule seed; expected: 10 × 404 `BOOKING_NOT_FOUND`,
then only 429 `RATE_LIMITED`.

## Adding a scenario
Every thread group in `airline-load.jmx` has the same shape. Copy one (`lookup` for a GET,
`booking` for a POST with a body), change its `testname`, its `${__P(..._threads,0)}` property
and the sampler's path or body, then give `scripts/load-test.sh` the knob: the defaults line,
the `-J` argument, and the label in the summary loop. The same file opens in the JMeter GUI
(download Apache JMeter, run `bin/jmeter`, File → Open): right-click the test plan → Add →
Threads (Users) → Thread Group, then Add → Sampler → HTTP Request, type the same `${__P(...)}`
expressions into the fields, save. The GUI is for building and debugging a plan (a "View
Results Tree" listener shows each request and response); load is always run headless (`-n`).

## Why JMeter
The widely used standard, a ready HTML report, nothing to install (it runs from a container)
and no code to maintain. The code-first alternatives are k6 (JavaScript) and Gatling
(Java/Scala), whose scripts read better in version control than a `.jmx` file; that is the
upgrade when load scripts need to live as code or gate a pipeline.
