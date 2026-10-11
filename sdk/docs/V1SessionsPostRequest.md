
# V1SessionsPostRequest


## Properties

Name | Type
------------ | -------------
`sport` | string
`format` | string
`ruleset` | [V1MatchesIdGet200ResponseRuleset](V1MatchesIdGet200ResponseRuleset.md)
`mode` | string
`source` | [V1SessionsPostRequestSource](V1SessionsPostRequestSource.md)
`calibrationInput` | [V1SessionsPostRequestCalibrationInput](V1SessionsPostRequestCalibrationInput.md)
`whoIsMe` | [V1SessionsPostRequestWhoIsMe](V1SessionsPostRequestWhoIsMe.md)
`freePlaySeed` | [V1SessionsPostRequestFreePlaySeed](V1SessionsPostRequestFreePlaySeed.md)
`rallyCandidates` | [Array&lt;V1SessionsPostRequestRallyCandidatesInner&gt;](V1SessionsPostRequestRallyCandidatesInner.md)
`uploadMode` | string

## Example

```typescript
import type { V1SessionsPostRequest } from '@elevate/sdk'

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
} satisfies V1SessionsPostRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1SessionsPostRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


