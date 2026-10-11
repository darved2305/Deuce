
# CreateChallengeRequestRoundsInner


## Properties

Name | Type
------------ | -------------
`hitEventId` | string
`landingEventId` | string
`contactMs` | number
`contactSigmaMs` | number
`ladderUs` | Array&lt;number&gt;

## Example

```typescript
import type { CreateChallengeRequestRoundsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "hitEventId": null,
  "landingEventId": null,
  "contactMs": null,
  "contactSigmaMs": null,
  "ladderUs": null,
} satisfies CreateChallengeRequestRoundsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateChallengeRequestRoundsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


