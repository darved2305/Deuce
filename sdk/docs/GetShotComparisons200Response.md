
# GetShotComparisons200Response


## Properties

Name | Type
------------ | -------------
`queryShotId` | string
`candidates` | [Array&lt;GetShotComparisons200ResponseCandidatesInner&gt;](GetShotComparisons200ResponseCandidatesInner.md)

## Example

```typescript
import type { GetShotComparisons200Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "queryShotId": null,
  "candidates": null,
} satisfies GetShotComparisons200Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetShotComparisons200Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


