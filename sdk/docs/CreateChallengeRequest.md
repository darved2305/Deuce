
# CreateChallengeRequest


## Properties

Name | Type
------------ | -------------
`matchId` | string
`displayName` | string
`rounds` | [Array&lt;CreateChallengeRequestRoundsInner&gt;](CreateChallengeRequestRoundsInner.md)
`score` | number
`roundResults` | [Array&lt;CreateChallengeRequestRoundResultsInner&gt;](CreateChallengeRequestRoundResultsInner.md)
`rematchOf` | string

## Example

```typescript
import type { CreateChallengeRequest } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "matchId": null,
  "displayName": null,
  "rounds": null,
  "score": null,
  "roundResults": null,
  "rematchOf": null,
} satisfies CreateChallengeRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateChallengeRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


