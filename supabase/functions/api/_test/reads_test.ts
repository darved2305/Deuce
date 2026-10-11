import assert from "node:assert/strict";
import type { Call, Handler } from "./fakes.ts";
import { makeApp, ok } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

type TableSource = unknown | ((call: Call) => unknown);
type ReadTables = Partial<
  Record<"matches" | "rallies" | "shots" | "moments", TableSource>
>;

function build(tables: ReadTables = {}) {
  const userCalls: Call[] = [];
  const adminCalls: Call[] = [];
  const user: Handler = (call) => {
    userCalls.push(call);
    if (call.kind !== "from") return ok(null);
    if (!(call.table in tables)) return ok([]);
    const source = tables[call.table as keyof ReadTables];
    return ok(typeof source === "function" ? source(call) : source);
  };
  const app = makeApp(user, () => ok(null), undefined, [], adminCalls);
  return { app, userCalls, adminCalls };
}

function fromCall(call: Call) {
  if (call.kind !== "from") throw new Error("expected a table query");
  return call;
}

function get(app: ReturnType<typeof build>["app"], path: string) {
  return app.fetch(
    new Request(`http://localhost/api${path}`, {
      headers: { Authorization: "Bearer jwt" },
    }),
  );
}

function matchRow(id: string, created_at: string, state?: string) {
  return {
    id,
    sport: "badminton",
    format: "singles",
    ruleset_id: "bwf_3x21",
    ruleset_version: "2025-04",
    created_at,
    score: { timeline: [], games: [] },
    analysis_runs: state ? [{ id: "a_1", state, created_at }] : [],
  };
}

function rallyRow(id: string, index: number, start_ms: number) {
  return {
    id,
    index,
    start_ms,
    end_ms: start_ms,
    shot_ids: [],
    server: "p_1",
    winner: "p_1",
    winner_state: "user",
    end_reason: "landing",
    ball_coverage: 0,
    clip_id: "c_r_1",
    state: "observed",
  };
}

function shotRow(id: string, index: number) {
  return {
    id,
    rally_id: "r_1",
    index,
    player_id: "p_1",
    contact_ms: index,
    contact_sigma_ms: 0,
    phase: "rally",
    reference_plane: "court",
    coordinate_frame_version: 1,
    roles: {
      focal: { player_id: "p_1", xy_m: [0, 0], state: "observed", conf: 1 },
      opponent: { player_id: "p_2", xy_m: [0, 0], state: "observed", conf: 1 },
    },
    incoming: {
      source_zone: "F1",
      landing_zone: "F1",
      zones_state: "observed",
      family: "unknown",
    },
    chosen: {
      target_zone: "F1",
      landing_xy_m: [0, 0],
      landing_state: "observed",
      family: "unknown",
    },
    flight_time_ms: 0,
    flight_time_state: "observed",
    context: {
      score_before: [0, 0],
      pressure_bucket: "unknown",
      rally_len_so_far: index,
      state: "observed",
    },
    outcome: { point_winner: "p_1", shots_to_end: 0, state: "user" },
    quality: { camera_quality_grade: "unknown" },
    clip_id: `c_sh_${index}`,
  };
}

function momentRow(id: string, match_id: string, start_ms: number) {
  return {
    id,
    match_id,
    rally_id: "r_1",
    shot_id: "sh_1",
    start_ms,
    end_ms: start_ms,
    contact_ms: start_ms,
    tags: ["back_corner"],
    clip_id: "c_sh_1",
  };
}

Deno.test("GET capabilities requires Bearer auth and conforms to capabilities.v1", async () => {
  const t = build();
  const unauthenticated = await t.app.fetch(
    new Request("http://localhost/api/v1/capabilities"),
  );
  assert.equal(unauthenticated.status, 401);
  conforms("error.envelope.v1", await unauthenticated.json());

  const res = await get(t.app, "/v1/capabilities");
  assert.equal(res.status, 200);
  const body = await res.json();
  conforms("capabilities.v1", body);
  assert.deepEqual(body.capabilities, {
    court: { tier: "assisted", status: "unknown" },
    ball: { tier: "experimental", status: "unknown" },
    reviews: { tier: "experimental", status: "unknown" },
    score: { tier: "assisted", status: "unknown" },
  });
  assert.equal(t.userCalls.length, 0);
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("GET matches pages latest-first with opaque cursor and user RLS", async () => {
  const rows = [
    matchRow("m_1", "2026-10-10T00:00:00Z", "processing"),
    matchRow("m_2", "2026-10-09T00:00:00Z", "completed"),
    matchRow("m_3", "2026-10-08T00:00:00Z"),
  ];
  const t = build({
    matches: (call: Call) =>
      call.kind === "from" && call.filters.or ? rows.slice(2) : rows,
  });
  const firstRes = await get(t.app, "/v1/matches?limit=2");
  assert.equal(firstRes.status, 200, await firstRes.clone().text());
  const first = await firstRes.json();
  conforms("matches.page.v1", first);
  assert.deepEqual(
    first.items.map((item: { match_id: string }) => item.match_id),
    [
      "m_1",
      "m_2",
    ],
  );
  assert.equal(first.items[0].state, "processing");
  assert.deepEqual(first.items[0].score, { games: [] });
  assert.equal(typeof first.next_cursor, "string");
  assert.equal(fromCall(t.userCalls[0]).filters.limit, 3);
  assert.equal(fromCall(t.userCalls[0]).filters["analysis_runs.limit"], 1);
  assert.deepEqual(
    (fromCall(t.userCalls[0]).filters["analysis_runs.order"] as {
      column: string;
    }[]).map((x) => x.column),
    ["created_at", "id"],
  );

  const secondRes = await get(
    t.app,
    `/v1/matches?limit=2&cursor=${encodeURIComponent(first.next_cursor)}`,
  );
  assert.equal(secondRes.status, 200, await secondRes.clone().text());
  const second = await secondRes.json();
  conforms("matches.page.v1", second);
  assert.deepEqual(
    second.items.map((item: { match_id: string }) => item.match_id),
    ["m_3"],
  );
  assert.equal(second.next_cursor, null);
  assert.equal(typeof fromCall(t.userCalls[1]).filters.or, "string");
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("GET matches returns an empty page for rows hidden by RLS", async () => {
  const t = build({ matches: [] });
  const res = await get(t.app, "/v1/matches");
  assert.equal(res.status, 200);
  const body = await res.json();
  conforms("matches.page.v1", body);
  assert.deepEqual(body, { items: [], next_cursor: null });
  assert.equal(t.userCalls.length, 1);
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("GET rallies pages match.v1 rally items with opaque cursor", async () => {
  const rows = [
    rallyRow("r_1", 0, 0),
    rallyRow("r_2", 1, 1),
    rallyRow("r_3", 2, 2),
  ];
  const t = build({
    rallies: (call: Call) =>
      call.kind === "from" && call.filters.or ? rows.slice(2) : rows,
  });
  const firstRes = await get(t.app, "/v1/matches/m_1/rallies?limit=2");
  assert.equal(firstRes.status, 200, await firstRes.clone().text());
  const first = await firstRes.json();
  conforms("rallies.page.v1", first);
  assert.deepEqual(
    first.items.map((item: { rally_id: string }) => item.rally_id),
    [
      "r_1",
      "r_2",
    ],
  );
  assert.equal(first.next_cursor.length > 0, true);
  assert.equal(fromCall(t.userCalls[0]).filters.match_id, "m_1");
  assert.equal(fromCall(t.userCalls[0]).filters.limit, 3);

  const secondRes = await get(
    t.app,
    `/v1/matches/m_1/rallies?limit=2&cursor=${
      encodeURIComponent(first.next_cursor)
    }`,
  );
  assert.equal(secondRes.status, 200, await secondRes.clone().text());
  const second = await secondRes.json();
  conforms("rallies.page.v1", second);
  assert.deepEqual(
    second.items.map((item: { rally_id: string }) => item.rally_id),
    ["r_3"],
  );
  assert.equal(second.next_cursor, null);
});

Deno.test("GET rally detail returns shots in shot_ids order and conforms to rally.detail.v1", async () => {
  const rally = { ...rallyRow("r_1", 0, 0), shot_ids: ["sh_2", "sh_1"] };
  const t = build({
    rallies: rally,
    shots: [shotRow("sh_1", 0), shotRow("sh_2", 1)],
  });
  const res = await get(t.app, "/v1/matches/m_1/rallies/r_1");
  assert.equal(res.status, 200, await res.clone().text());
  const body = await res.json();
  conforms("rally.detail.v1", body);
  assert.deepEqual(
    body.shots.map((shot: { shot_id: string }) => shot.shot_id),
    [
      "sh_2",
      "sh_1",
    ],
  );
  assert.deepEqual(
    t.userCalls.map((call) => call.kind === "from" ? call.table : "rpc"),
    [
      "rallies",
      "shots",
    ],
  );
  assert.equal(fromCall(t.userCalls[1]).filters.rally_id, "r_1");
  assert.deepEqual(fromCall(t.userCalls[1]).filters["id in"], ["sh_2", "sh_1"]);
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("GET rally detail maps an RLS-hidden rally to 404", async () => {
  const t = build({ rallies: null });
  const res = await get(t.app, "/v1/matches/m_hidden/rallies/r_hidden");
  assert.equal(res.status, 404);
  const body = await res.json();
  conforms("error.envelope.v1", body);
  assert.equal(body.error.code, "not_found");
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("GET match moments and cross-match moments apply tag and match filters", async () => {
  const row = momentRow("mo_1", "m_1", 0);
  const match = build({ moments: [row] });
  const matchRes = await get(
    match.app,
    "/v1/matches/m_1/moments?tag=back_corner",
  );
  assert.equal(matchRes.status, 200, await matchRes.clone().text());
  const matchBody = await matchRes.json();
  conforms("moments.page.v1", matchBody);
  assert.deepEqual(matchBody.items, [{
    moment_id: "mo_1",
    match_id: "m_1",
    rally_id: "r_1",
    shot_id: "sh_1",
    start_ms: 0,
    end_ms: 0,
    contact_ms: 0,
    tags: ["back_corner"],
    clip_id: "c_sh_1",
  }]);
  assert.deepEqual(fromCall(match.userCalls[0]).filters.tags_contains, [
    "back_corner",
  ]);
  assert.equal(fromCall(match.userCalls[0]).filters.match_id, "m_1");

  const all = build({ moments: [row] });
  const allRes = await get(
    all.app,
    "/v1/moments?tag=back_corner&match_id=m_1&limit=20",
  );
  assert.equal(allRes.status, 200, await allRes.clone().text());
  const allBody = await allRes.json();
  conforms("moments.page.v1", allBody);
  assert.equal(allBody.items[0].match_id, "m_1");
  assert.deepEqual(fromCall(all.userCalls[0]).filters.tags_contains, [
    "back_corner",
  ]);
  assert.equal(fromCall(all.userCalls[0]).filters.match_id, "m_1");
  assert.equal(all.adminCalls.length, 0);
});

Deno.test("GET /v1/moments continues an opaque cursor within the same filters", async () => {
  const rows = [
    momentRow("mo_1", "m_1", 0),
    momentRow("mo_2", "m_1", 1),
    momentRow("mo_3", "m_2", 2),
  ];
  const t = build({
    moments: (call: Call) =>
      call.kind === "from" && call.filters.or ? rows.slice(1, 2) : rows,
  });
  const firstRes = await get(t.app, "/v1/moments?tag=back_corner&limit=1");
  assert.equal(firstRes.status, 200, await firstRes.clone().text());
  const first = await firstRes.json();
  conforms("moments.page.v1", first);
  assert.equal(first.items[0].moment_id, "mo_1");
  assert.equal(typeof first.next_cursor, "string");

  const secondRes = await get(
    t.app,
    `/v1/moments?tag=back_corner&limit=1&cursor=${
      encodeURIComponent(first.next_cursor)
    }`,
  );
  assert.equal(secondRes.status, 200, await secondRes.clone().text());
  const second = await secondRes.json();
  conforms("moments.page.v1", second);
  assert.equal(second.items[0].moment_id, "mo_2");
  assert.equal(second.next_cursor, null);
  assert.equal(typeof fromCall(t.userCalls[1]).filters.or, "string");
  assert.deepEqual(fromCall(t.userCalls[1]).filters.tags_contains, [
    "back_corner",
  ]);
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("RLS-hidden list reads return empty pages without a parent-match 404", async () => {
  const t = build({ matches: [], rallies: [], moments: [] });
  for (
    const [path, schema] of [
      ["/v1/matches/m_hidden/rallies", "rallies.page.v1"],
      ["/v1/matches/m_hidden/moments", "moments.page.v1"],
      ["/v1/moments?match_id=m_hidden", "moments.page.v1"],
    ] as const
  ) {
    const res = await get(t.app, path);
    assert.equal(res.status, 200, await res.clone().text());
    const body = await res.json();
    conforms(schema, body);
    assert.deepEqual(body, { items: [], next_cursor: null });
  }
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("read routes enforce list limits and reject invalid cursors", async () => {
  const t = build();
  for (
    const path of [
      "/v1/matches?limit=101",
      "/v1/matches/m_1/rallies?limit=101",
      "/v1/matches/m_1/moments?limit=21",
      "/v1/moments?limit=21",
      "/v1/matches?cursor=not-a-cursor",
    ]
  ) {
    const res = await get(t.app, path);
    assert.equal(res.status, 422, `${path}: ${await res.clone().text()}`);
    conforms("error.envelope.v1", await res.json());
  }
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("all read routes are registered in the public OpenAPI document", async () => {
  const t = build();
  const res = await t.app.fetch(
    new Request("http://localhost/api/v1/openapi.json"),
  );
  assert.equal(res.status, 200);
  const document = await res.json();
  for (
    const path of [
      "/v1/capabilities",
      "/v1/matches",
      "/v1/matches/{id}/rallies",
      "/v1/matches/{id}/rallies/{rid}",
      "/v1/matches/{id}/moments",
      "/v1/moments",
    ]
  ) {
    assert.ok(document.paths[path]?.get, path);
  }
  assert.equal(t.adminCalls.length, 0);
});
