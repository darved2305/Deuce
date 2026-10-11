
# CreateSessionRequestSource


## Properties

Name | Type
------------ | -------------
`width` | number
`height` | number
`rotation` | number
`fpsNominal` | number
`durationMs` | number
`pts` | [CreateSessionRequestSourcePts](CreateSessionRequestSourcePts.md)
`codec` | string
`hasAudio` | boolean
`audioRateHz` | number
`device` | string
`bytes` | number
`sha256` | string

## Example

```typescript
import type { CreateSessionRequestSource } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "width": null,
  "height": null,
  "rotation": null,
  "fpsNominal": null,
  "durationMs": null,
  "pts": null,
  "codec": null,
  "hasAudio": null,
  "audioRateHz": null,
  "device": null,
  "bytes": null,
  "sha256": null,
} satisfies CreateSessionRequestSource

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateSessionRequestSource
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


