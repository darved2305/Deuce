import assert from "node:assert/strict";
import type { Call, Handler } from "./fakes.ts";
import { makeApp, ok } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

function calibration() {
  return {
    calibration_id: "cal_1",
    plane: "court",
    template: "badminton_singles",
    method: "tap",
    H: [1, 0, 0, 0, 1, 0, 0, 0, 1],
    H_cov: null,
    residual_px: 0,
    visible_fraction: 1,
    tier: "A",
  };
}

function matchV1(
  matchId: string,
  calibrated: boolean,
  state = "completed",
  artifacts: { ball_track: string | null; player_tracks: string | null } = {
    ball_track: "art_ball",
    player_tracks: "art_players",
  },
) {
  return {
    schema: "match.v1",
    match_id: matchId,
    session_ids: ["s_1"],
    sport: "badminton",
    format: "singles",
    ruleset: { id: "bwf_3x21", version: "2025-04" },
    players: [],
    calibration: calibrated ? calibration() : null,
    analysis: {
      analysis_id: "a_1",
      engine_version: "0.3.0",
      state,
      models: [],
      artifacts,
    },
    capabilities: {
      court: { tier: "core", status: "unknown" },
      ball: { tier: "core", status: "unknown" },
      reviews: { tier: "core", status: "unknown" },
      score: { tier: "core", status: "unknown" },
    },
    rallies: [],
    shots: [],
    events: [],
    reviews: [],
    confirmed_points: [],
    score: { timeline: [], games: [] },
    metrics: [],
    insights: [],
    moments: [],
    clips: [],
    corrections: [],
  };
}

function build(
  resultFor: (matchId: string) => unknown,
  visibleMatch: (matchId: string) => boolean = () => false,
) {
  const userCalls: Call[] = [];
  const adminCalls: Call[] = [];
  const user: Handler = (call) => {
    userCalls.push(call);
    if (call.kind === "rpc" && call.name === "get_match_v1") {
      return ok(resultFor(String(call.args.p_match_id)));
    }
    if (call.kind === "from" && call.table === "matches") {
      const matchId = String(call.filters.id);
      return ok(visibleMatch(matchId) ? { id: matchId } : null);
    }
    return ok(null);
  };
  const admin: Handler = () => ok(null);
  const app = makeApp(user, admin, undefined, [], adminCalls);
  return { app, userCalls, adminCalls };
}

function getMatch(
  app: ReturnType<typeof build>["app"],
  matchId: string,
  jwt = "jwt",
) {
  return app.fetch(
    new Request(`http://localhost/api/v1/matches/${matchId}`, {
      headers: { Authorization: `Bearer ${jwt}` },
    }),
  );
}

Deno.test("GET match returns schema-valid uncalibrated and calibrated match.v1", async () => {
  for (const calibrated of [false, true]) {
    const expected = matchV1("m_1", calibrated);
    const t = build(() => expected);
    const res = await getMatch(t.app, "m_1");
    assert.equal(res.status, 200, await res.clone().text());
    const body = await res.json();
    conforms("match.v1", body);
    assert.deepEqual(body, expected);
    assert.equal(t.userCalls.length, 1);
    assert.ok(t.userCalls[0].kind === "rpc");
    assert.equal(t.userCalls[0].name, "get_match_v1");
    assert.equal(t.adminCalls.length, 0);
  }
});

Deno.test("GET match maps invisible and unknown records to 404", async () => {
  const t = build(() => null);
  for (const matchId of ["m_hidden", "m_unknown"]) {
    const res = await getMatch(t.app, matchId);
    assert.equal(res.status, 404);
    assert.equal((await res.json()).error.code, "not_found");
  }
});

Deno.test("GET match returns 409 while a visible match analysis is incomplete", async () => {
  const cases = [
    {
      app: build(() => null, () => true).app,
      matchId: "m_no_analysis",
    },
    ...[
      "awaiting_upload",
      "uploaded",
      "queued",
      "processing",
      "partial",
      "finalizing",
      "failed",
    ].map((state) => ({
      app: build(() => matchV1(`m_${state}`, false, state), () => true).app,
      matchId: `m_${state}`,
    })),
  ];
  for (const { app, matchId } of cases) {
    const res = await getMatch(app, matchId);
    assert.equal(res.status, 409);
    const body = await res.json();
    assert.deepEqual(body.error, {
      code: "conflict",
      message: "analysis not complete",
    });
    conforms("error.envelope.v1", body);
  }
});

Deno.test("GET match accepts null whole-match track artifact ids", async () => {
  const expected = matchV1("m_no_tracks", false, "completed", {
    ball_track: null,
    player_tracks: null,
  });
  const t = build(() => expected);
  const res = await getMatch(t.app, "m_no_tracks");
  assert.equal(res.status, 200, await res.clone().text());
  const body = await res.json();
  conforms("match.v1", body);
  assert.deepEqual(body.analysis.artifacts, {
    ball_track: null,
    player_tracks: null,
  });
});

Deno.test("GET match uses the user RPC for a share-grant reader fake", async () => {
  const expected = matchV1("m_shared", false);
  const t = build((matchId) => matchId === "m_shared" ? expected : null);
  const res = await getMatch(t.app, "m_shared", "share-reader-jwt");
  assert.equal(res.status, 200, await res.clone().text());
  conforms("match.v1", await res.json());
  assert.equal(t.userCalls.length, 1);
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("GET match returns internal when the RPC result violates match.v1", async () => {
  const t = build(() => ({ ...matchV1("m_1", false), unexpected: true }));
  const res = await getMatch(t.app, "m_1");
  assert.equal(res.status, 500);
  assert.equal((await res.json()).error.code, "internal");
});

Deno.test("GET and DELETE match appear in the public OpenAPI document", async () => {
  const t = build(() => null);
  const res = await t.app.fetch(
    new Request("http://localhost/api/v1/openapi.json"),
  );
  assert.equal(res.status, 200, await res.clone().text());
  const doc = await res.json();
  assert.ok(doc.paths["/v1/matches/{id}"].get);
  assert.ok(doc.paths["/v1/matches/{id}"].delete);
  assert.equal(
    doc.paths["/v1/matches/{id}"].delete.responses["202"].content,
    undefined,
  );
});

function buildDeleteMatch(
  matchId = "m_delete",
  options: {
    ownsMatch?: boolean;
    analysisState?: string;
    failKey?: string;
    failAttempts?: number;
  } = {},
) {
  const adminCalls: Call[] = [];
  const order: string[] = [];
  const tasks: Promise<unknown>[] = [];
  const r2Attempts = new Map<string, number>();
  const user: Handler = (call) => {
    if (call.kind === "from" && call.table === "matches") {
      assert.deepEqual(call.filters, { id: matchId, owner_id: "u1" });
      return ok(options.ownsMatch === false ? null : { id: matchId });
    }
    return ok(null);
  };
  const keys = [
    "orig/s_1/source.mp4",
    "analysis/a_1/0.3.0/windows/0.json",
    `clips/${matchId}/c_1.mp4`,
    `clips/${matchId}/c_1.jpg`,
  ];
  const admin: Handler = (call) => {
    if (call.kind === "rpc" && call.name === "delete_match") {
      if (
        options.analysisState &&
        !["completed", "failed"].includes(options.analysisState)
      ) {
        order.push("rpc-rejected");
        return {
          data: null,
          error: { code: "P0001", message: "analysis_in_progress" },
        };
      }
      order.push("db-commit");
      return ok(keys);
    }
    return ok(null);
  };
  const r2 = {
    createMultipart: () => Promise.resolve("upload"),
    completeMultipart: () => Promise.resolve(),
    abortMultipart: () => Promise.resolve(),
    head: () => Promise.resolve(null),
    deleteObject: (key: string) => {
      order.push(`object:${key}`);
      const attempts = (r2Attempts.get(key) ?? 0) + 1;
      r2Attempts.set(key, attempts);
      return key === options.failKey && attempts <= (options.failAttempts ?? 0)
        ? Promise.reject(new Error("R2 delete failed"))
        : Promise.resolve();
    },
    presignPart: () => Promise.resolve("url"),
    presignPut: () => Promise.resolve("url"),
    presignGet: () => Promise.resolve("url"),
  };
  const app = makeApp(
    user,
    admin,
    undefined,
    [],
    adminCalls,
    r2,
    (task) => {
      tasks.push(task);
    },
  );
  return { app, adminCalls, order, tasks, r2Attempts };
}

function deleteMatch(
  app: ReturnType<typeof buildDeleteMatch>["app"],
  matchId: string,
) {
  return app.fetch(
    new Request(`http://localhost/api/v1/matches/${matchId}`, {
      method: "DELETE",
      headers: { Authorization: "Bearer jwt" },
    }),
  );
}

Deno.test("DELETE match returns 409 while analysis is in progress", async () => {
  const t = buildDeleteMatch("m_live", { analysisState: "processing" });
  const res = await deleteMatch(t.app, "m_live");
  assert.equal(res.status, 409);
  const body = await res.json();
  assert.deepEqual(body.error, {
    code: "conflict",
    message: "analysis in progress",
  });
  conforms("error.envelope.v1", body);
  assert.deepEqual(t.order, ["rpc-rejected"]);
  assert.equal(t.adminCalls.length, 1);
  assert.equal(t.tasks.length, 0);
  assert.equal(t.r2Attempts.size, 0);
});

Deno.test("DELETE match returns 202 for terminal analyses and cleans up after commit", async () => {
  for (const analysisState of ["completed", "failed"]) {
    const t = buildDeleteMatch("m_delete", { analysisState });
    const res = await deleteMatch(t.app, "m_delete");
    assert.equal(res.status, 202, await res.clone().text());
    assert.equal(await res.text(), "");
    assert.equal(t.order[0], "db-commit");
    const task = t.tasks[0];
    assert.ok(task);
    await task;
    assert.equal(t.order[0], "db-commit");
    assert.deepEqual(
      t.order.slice(1).sort(),
      [
        "object:orig/s_1/source.mp4",
        "object:analysis/a_1/0.3.0/windows/0.json",
        "object:clips/m_delete/c_1.mp4",
        "object:clips/m_delete/c_1.jpg",
      ].sort(),
    );
    assert.deepEqual(t.adminCalls, [{
      kind: "rpc",
      name: "delete_match",
      args: { p_match_id: "m_delete", p_owner_id: "u1" },
    }]);
  }
});

Deno.test("DELETE match hides non-owned rows and schedules no cleanup", async () => {
  const t = buildDeleteMatch("m_hidden", { ownsMatch: false });
  const res = await deleteMatch(t.app, "m_hidden");
  assert.equal(res.status, 404);
  const body = await res.json();
  assert.deepEqual(body.error, {
    code: "not_found",
    message: "match not found",
  });
  conforms("error.envelope.v1", body);
  assert.equal(t.adminCalls.length, 0);
  assert.equal(t.tasks.length, 0);
  assert.deepEqual(t.order, []);
});

Deno.test("DELETE match retries R2 cleanup idempotently after database commit", async () => {
  const key = "orig/s_1/source.mp4";
  const t = buildDeleteMatch("m_delete", { failKey: key, failAttempts: 2 });
  const res = await deleteMatch(t.app, "m_delete");
  assert.equal(res.status, 202);
  const task = t.tasks[0];
  assert.ok(task);
  await task;
  assert.equal(t.order[0], "db-commit");
  assert.equal(t.r2Attempts.get(key), 3);
});
