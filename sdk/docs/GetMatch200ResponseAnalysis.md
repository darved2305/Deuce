
# GetMatch200ResponseAnalysis


## Properties

Name | Type
------------ | -------------
`analysisId` | string
`engineVersion` | string
`state` | string
`models` | Array&lt;string&gt;
`artifacts` | [GetMatch200ResponseAnalysisArtifacts](GetMatch200ResponseAnalysisArtifacts.md)

## Example

```typescript
import type { GetMatch200ResponseAnalysis } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "analysisId": null,
  "engineVersion": null,
  "state": null,
  "models": null,
  "artifacts": null,
} satisfies GetMatch200ResponseAnalysis

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetMatch200ResponseAnalysis
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


