const SRC = new URL("../../packages/rules/src/", import.meta.url);
const DEST = new URL("../functions/api/lib/rules_vendor/", import.meta.url);
const HEADER = "// GENERATED from packages/rules - do not edit.\n" +
  "// Regenerate with supabase/scripts/sync-rules.ts\n";

async function listFiles(dir: URL, prefix = ""): Promise<string[]> {
  const out: string[] = [];
  for await (const entry of Deno.readDir(dir)) {
    if (entry.isDirectory) {
      out.push(
        ...await listFiles(
          new URL(`${entry.name}/`, dir),
          `${prefix}${entry.name}/`,
        ),
      );
    } else if (entry.name.endsWith(".ts")) {
      out.push(`${prefix}${entry.name}`);
    }
  }
  return out.sort();
}

export function transform(source: string): string {
  const lf = source.replace(/\r\n/g, "\n");
  const fixed = lf.replace(
    /(from\s+")((?:\.{1,2})\/[^\"]*?)(")/g,
    (_match, before: string, path: string, after: string) =>
      `${before}${/\.[a-z]+$/.test(path) ? path : `${path}.ts`}${after}`,
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
      have = [];
    }
    for (const [name, text] of want) {
      let current: string | null = null;
      try {
        current = await Deno.readTextFile(new URL(name, DEST));
      } catch {
        current = null;
      }
      if (current !== text) stale.push(name);
    }
    for (const name of have) if (!want.has(name)) stale.push(`${name} (extra)`);
    if (stale.length) {
      console.error(`rules_vendor is stale: ${stale.join(", ")}`);
      Deno.exit(1);
    }
    console.log("rules_vendor is up to date");
  } else {
    await Deno.remove(DEST, { recursive: true }).catch(() => {});
    await Deno.mkdir(DEST, { recursive: true });
    for (const [name, text] of want) {
      await Deno.writeTextFile(new URL(name, DEST), text);
    }
    console.log(`vendored ${want.size} files`);
  }
}
