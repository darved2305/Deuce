
# CreateChallenge201ResponseEntriesInner


## Properties

Name | Type
------------ | -------------
`playerId` | string
`displayName` | string
`score` | number
`roundResults` | [Array&lt;CreateChallengeRequestRoundResultsInner&gt;](CreateChallengeRequestRoundResultsInner.md)
`outcome` | string
`acceptedAt` | Date
`playedAt` | Date

## Example

```typescript
import type { CreateChallenge201ResponseEntriesInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "playerId": null,
  "displayName": null,
  "score": null,
  "roundResults": null,
  "outcome": null,
  "acceptedAt": null,
  "playedAt": null,
} satisfies CreateChallenge201ResponseEntriesInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateChallenge201ResponseEntriesInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


