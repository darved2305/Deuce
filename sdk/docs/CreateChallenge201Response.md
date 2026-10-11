
# CreateChallenge201Response


## Properties

Name | Type
------------ | -------------
`id` | string
`matchId` | string
`status` | string
`videoSha256` | string
`rematchOf` | string
`rounds` | [Array&lt;CreateChallengeRequestRoundsInner&gt;](CreateChallengeRequestRoundsInner.md)
`creator` | [CreateChallenge201ResponseCreator](CreateChallenge201ResponseCreator.md)
`entries` | [Array&lt;CreateChallenge201ResponseEntriesInner&gt;](CreateChallenge201ResponseEntriesInner.md)
`viewer` | [CreateChallenge201ResponseViewer](CreateChallenge201ResponseViewer.md)
`createdAt` | Date

## Example

```typescript
import type { CreateChallenge201Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "matchId": null,
  "status": null,
  "videoSha256": null,
  "rematchOf": null,
  "rounds": null,
  "creator": null,
  "entries": null,
  "viewer": null,
  "createdAt": null,
} satisfies CreateChallenge201Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateChallenge201Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


