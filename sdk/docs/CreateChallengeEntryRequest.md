
# CreateChallengeEntryRequest


## Properties

Name | Type
------------ | -------------
`displayName` | string
`score` | number
`roundResults` | [Array&lt;CreateChallengeRequestRoundResultsInner&gt;](CreateChallengeRequestRoundResultsInner.md)

## Example

```typescript
import type { CreateChallengeEntryRequest } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "displayName": null,
  "score": null,
  "roundResults": null,
} satisfies CreateChallengeEntryRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateChallengeEntryRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


