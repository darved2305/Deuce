// Vendors packages/contracts/src into the Edge Function (D-261010-HW2).
//   deno run --allow-read --allow-write supabase/scripts/sync-contracts.ts          write
//   deno run --allow-read supabase/scripts/sync-contracts.ts --check                fail if stale
// Output is deterministic: sorted files, LF endings, relative imports get explicit .ts
// extensions, and the bare `zod` import already matches the import map in api/deno.json.

const SRC = new URL("../../packages/contracts/src/", import.meta.url);
const DEST = new URL("../functions/api/lib/contracts_vendor/", import.meta.url);
const HEADER = "// GENERATED from packages/contracts - do not edit.\n" +
  "// Regenerate with supabase/scripts/sync-contracts.ts\n";

async function listFiles(dir: URL, prefix = ""): Promise<string[]> {
  const out: string[] = [];
  for await (const e of Deno.readDir(dir)) {
    if (e.isDirectory) {
      out.push(
        ...await listFiles(new URL(`${e.name}/`, dir), `${prefix}${e.name}/`),
      );
    } else if (e.name.endsWith(".ts")) {
      out.push(`${prefix}${e.name}`);
    }
  }
  return out.sort();
}

export function transform(source: string): string {
  const lf = source.replace(/\r\n/g, "\n");
  const fixed = lf.replace(
    /(from\s+")(\.{1,2}\/[^"]*?)(")/g,
    (_m, a: string, p: string, b: string) =>
      `${a}${/\.[a-z]+$/.test(p) ? p : `${p}.ts`}${b}`,
  );
  return HEADER + fixed;
}

async function expected(): Promise<Map<string, string>> {
  const files = new Map<string, string>();
  for (const name of await listFiles(SRC)) {
    files.set(name, transform(await Deno.readTextFile(new URL(name, SRC))));
  }
  return files;
}

if (import.meta.main) {
  const check = Deno.args.includes("--check");
  const want = await expected();
  if (check) {
    const stale: string[] = [];
    let have: string[] = [];
    try {
      have = await listFiles(DEST);
    } catch {
      // missing vendor dir counts as stale
    }
    for (const [name, text] of want) {
      let cur: string | null = null;
      try {
        cur = await Deno.readTextFile(new URL(name, DEST));
      } catch {
        cur = null;
      }
      if (cur !== text) stale.push(name);
    }
    for (const name of have) if (!want.has(name)) stale.push(`${name} (extra)`);
    if (stale.length) {
      console.error(`contracts_vendor is stale: ${stale.join(", ")}`);
      Deno.exit(1);
    }
    console.log("contracts_vendor is up to date");
  } else {
    await Deno.remove(DEST, { recursive: true }).catch(() => {});
    await Deno.mkdir(DEST, { recursive: true });
    for (const [name, text] of want) {
      await Deno.writeTextFile(new URL(name, DEST), text);
    }
    console.log(`vendored ${want.size} files`);
  }
}
