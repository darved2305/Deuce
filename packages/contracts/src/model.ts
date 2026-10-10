import * as z from "zod";
import { canonicalModelIdSchema, sha256Schema } from "./common.ts";

export const modelDescriptorSchema = z.strictObject({
  id: canonicalModelIdSchema,
  name: z.string(),
  source_url: z.string().url(),
  file: z.string(),
  sha256: sha256Schema,
  bytes: z.number().int().nonnegative(),
  code_license: z.string(),
  weights_license: z.string(),
  trained_on: z.array(z.string()),
  used_for: z.array(z.string()),
});

export type ModelDescriptor = z.infer<typeof modelDescriptorSchema>;
