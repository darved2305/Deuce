export function env(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`missing env: ${name}`);
  return value;
}
