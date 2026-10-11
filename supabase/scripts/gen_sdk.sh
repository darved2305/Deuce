#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
sdk_dir="$repo_root/sdk"
openapi_url="https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api/v1/openapi.json"
openapi_file="$(mktemp "$sdk_dir/.openapi.XXXXXX")"
trap 'rm -f "$openapi_file"' EXIT

curl --fail --location --silent --show-error "$openapi_url" --output "$openapi_file"

volume_root="$repo_root"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) volume_root="$(cygpath -m "$repo_root")" ;;
esac
openapi_name="$(basename "$openapi_file")"

MSYS_NO_PATHCONV=1 docker run --rm \
  --volume "$volume_root:/workspace" \
  --workdir /workspace \
  openapitools/openapi-generator-cli:v7.26.0 \
  generate \
  -i "/workspace/sdk/$openapi_name" \
  -g typescript-fetch \
  -o /workspace/sdk/ \
  --additional-properties=supportsES6=true,npmName=@elevate/sdk

node - "$sdk_dir" <<'NODE'
const fs = require("node:fs");
const path = require("node:path");
const sdkDir = process.argv[2];
const packagePath = path.join(sdkDir, "package.json");
const packageJson = JSON.parse(fs.readFileSync(packagePath, "utf8"));
packageJson.devDependencies = {
  ...packageJson.devDependencies,
  typescript: "7.0.2",
};
fs.writeFileSync(packagePath, `${JSON.stringify(packageJson, null, 2)}\n`);

const readmePath = path.join(sdkDir, "README.md");
const readme = fs.readFileSync(readmePath, "utf8");
const generatedAuthorization = "Endpoints do not require authorization.";
if (!readme.includes(generatedAuthorization)) {
  throw new Error("Expected OpenAPI Generator authorization text not found.");
}
fs.writeFileSync(
  readmePath,
  readme.replace(
    generatedAuthorization,
    'Protected endpoints require a Supabase bearer JWT. Pass it as `new Configuration({ headers: { Authorization: "Bearer <access-token>" } })`; `/v1/health` is public.',
  ),
);
NODE
