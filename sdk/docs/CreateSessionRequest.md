
# CreateSessionRequest


## Properties

Name | Type
------------ | -------------
`sport` | string
`format` | string
`ruleset` | [GetMatch200ResponseRuleset](GetMatch200ResponseRuleset.md)
`mode` | string
`source` | [CreateSessionRequestSource](CreateSessionRequestSource.md)
`calibrationInput` | [CreateSessionRequestCalibrationInput](CreateSessionRequestCalibrationInput.md)
`whoIsMe` | [CreateSessionRequestWhoIsMe](CreateSessionRequestWhoIsMe.md)
`freePlaySeed` | [CreateSessionRequestFreePlaySeed](CreateSessionRequestFreePlaySeed.md)
`rallyCandidates` | [Array&lt;CreateSessionRequestRallyCandidatesInner&gt;](CreateSessionRequestRallyCandidatesInner.md)
`uploadMode` | string

## Example

```typescript
import type { CreateSessionRequest } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "sport": null,
  "format": null,
  "ruleset": null,
  "mode": null,
  "source": null,
  "calibrationInput": null,
  "whoIsMe": null,
  "freePlaySeed": null,
  "rallyCandidates": null,
  "uploadMode": null,
} satisfies CreateSessionRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateSessionRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


