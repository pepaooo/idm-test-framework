# IdM test workspace

Folders:

- `scenarios/` - your test scenarios (`.yaml`); start from `example.yaml`
- `config/` - one settings file per environment: `application-<profile>.yml`
- `drivers/` - extra JDBC driver jars (see `drivers/README.md`)
- `reports/` - every run writes its own folder here (`report.html`, `results.json`, `run.log`)
- `secrets.env` - passwords, copied from `secrets.env.example` (never commit it)

Commands:

```
idmtest validate                     check the scenarios, connecting to nothing
idmtest validate --profile lab       also check the settings of the lab profile
idmtest run --profile lab            run the scenarios against the lab systems
idmtest run --profile lab --scenario 'example*'    run only matching scenarios
```

Open `reports/<run-id>/report.html` in a browser to see the result of a run.

Documentation: `docs/reference/writing-scenarios.md` (steps) and
`docs/reference/configuration.md` (settings) in the framework's repository.
