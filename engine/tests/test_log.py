import json

from elevate_engine.log import log


def test_log_writes_one_json_line(capsys):
    log("audit.done", clip="eurogames_bern_2023.webm", elapsed_ms=12)
    lines = capsys.readouterr().out.splitlines()
    assert len(lines) == 1
    record = json.loads(lines[0])
    assert record["event"] == "audit.done"
    assert record["clip"] == "eurogames_bern_2023.webm"
    assert record["elapsed_ms"] == 12
