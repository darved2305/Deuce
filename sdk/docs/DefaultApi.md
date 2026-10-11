# DefaultApi

All URIs are relative to *https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**abortUpload**](DefaultApi.md#abortupload) | **POST** /v1/sessions/{id}/upload/abort |  |
| [**acceptChallenge**](DefaultApi.md#acceptchallengeoperation) | **POST** /v1/challenges/{id}/accept |  |
| [**completeSegment**](DefaultApi.md#completesegment) | **POST** /v1/sessions/{id}/segments/{seq}/complete |  |
| [**completeUpload**](DefaultApi.md#completeuploadoperation) | **POST** /v1/sessions/{id}/upload/complete |  |
| [**confirmReview**](DefaultApi.md#confirmreviewoperation) | **POST** /v1/matches/{id}/reviews/{rvid}/confirm |  |
| [**createAnalysis**](DefaultApi.md#createanalysisoperation) | **POST** /v1/analyses |  |
| [**createChallenge**](DefaultApi.md#createchallengeoperation) | **POST** /v1/challenges |  |
| [**createChallengeEntry**](DefaultApi.md#createchallengeentryoperation) | **POST** /v1/challenges/{id}/entries |  |
| [**createCorrection**](DefaultApi.md#createcorrectionoperation) | **POST** /v1/matches/{id}/corrections |  |
| [**createDeveloperKey**](DefaultApi.md#createdeveloperkey) | **POST** /v1/developer-keys |  |
| [**createSession**](DefaultApi.md#createsessionoperation) | **POST** /v1/sessions |  |
| [**createUploadParts**](DefaultApi.md#createuploadpartsoperation) | **POST** /v1/sessions/{id}/upload/parts |  |
| [**deleteMatch**](DefaultApi.md#deletematch) | **DELETE** /v1/matches/{id} |  |
| [**getAnalysis**](DefaultApi.md#getanalysis) | **GET** /v1/analyses/{id} |  |
| [**getArtifact**](DefaultApi.md#getartifact) | **GET** /v1/artifacts/{id} |  |
| [**getCapabilities**](DefaultApi.md#getcapabilities) | **GET** /v1/capabilities |  |
| [**getChallenge**](DefaultApi.md#getchallenge) | **GET** /v1/challenges/{id} |  |
| [**getHealth**](DefaultApi.md#gethealth) | **GET** /v1/health |  |
| [**getMatch**](DefaultApi.md#getmatch) | **GET** /v1/matches/{id} |  |
| [**getRally**](DefaultApi.md#getrally) | **GET** /v1/matches/{id}/rallies/{rid} |  |
| [**getSession**](DefaultApi.md#getsession) | **GET** /v1/sessions/{id} |  |
| [**getShotComparisons**](DefaultApi.md#getshotcomparisons) | **GET** /v1/matches/{mid}/shots/{shot_id}/comparisons |  |
| [**listAnalysisEvents**](DefaultApi.md#listanalysisevents) | **GET** /v1/analyses/{id}/events |  |
| [**listFriends**](DefaultApi.md#listfriends) | **GET** /v1/friends |  |
| [**listMatchMoments**](DefaultApi.md#listmatchmoments) | **GET** /v1/matches/{id}/moments |  |
| [**listMatches**](DefaultApi.md#listmatches) | **GET** /v1/matches |  |
| [**listMoments**](DefaultApi.md#listmoments) | **GET** /v1/moments |  |
| [**listRallies**](DefaultApi.md#listrallies) | **GET** /v1/matches/{id}/rallies |  |
| [**registerSegment**](DefaultApi.md#registersegmentoperation) | **POST** /v1/sessions/{id}/segments |  |
| [**revokeDeveloperKey**](DefaultApi.md#revokedeveloperkey) | **DELETE** /v1/developer-keys/{id} |  |



## abortUpload

> AbortUpload200Response abortUpload(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { AbortUploadRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies AbortUploadRequest;

  try {
    const data = await api.abortUpload(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |

### Return type

[**AbortUpload200Response**](AbortUpload200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | upload aborted (idempotent) |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## acceptChallenge

> CreateChallenge201Response acceptChallenge(id, acceptChallengeRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { AcceptChallengeOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // AcceptChallengeRequest (optional)
    acceptChallengeRequest: ...,
  } satisfies AcceptChallengeOperationRequest;

  try {
    const data = await api.acceptChallenge(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **acceptChallengeRequest** | [AcceptChallengeRequest](AcceptChallengeRequest.md) |  | [Optional] |

### Return type

[**CreateChallenge201Response**](CreateChallenge201Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | challenge accepted; the caller can now read the match through a share grant |  -  |
| **401** | missing or invalid token |  -  |
| **404** | challenge or match not visible to the caller |  -  |
| **409** | conflict with the stored challenge |  -  |
| **422** | invalid request |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## completeSegment

> CompleteSegment200Response completeSegment(id, seq)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { CompleteSegmentRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // string
    seq: seq_example,
  } satisfies CompleteSegmentRequest;

  try {
    const data = await api.completeSegment(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **seq** | `string` |  | [Defaults to `undefined`] |

### Return type

[**CompleteSegment200Response**](CompleteSegment200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | verified segment manifest (idempotent) |  -  |
| **409** | segment object is missing or has the wrong size |  -  |
| **503** | R2 usage could not be recorded |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## completeUpload

> CompleteUpload200Response completeUpload(id, completeUploadRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { CompleteUploadOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // CompleteUploadRequest (optional)
    completeUploadRequest: ...,
  } satisfies CompleteUploadOperationRequest;

  try {
    const data = await api.completeUpload(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **completeUploadRequest** | [CompleteUploadRequest](CompleteUploadRequest.md) |  | [Optional] |

### Return type

[**CompleteUpload200Response**](CompleteUpload200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | upload completed (idempotent) |  -  |
| **409** | uploaded object size does not match source.bytes |  -  |
| **503** | R2 usage could not be recorded |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## confirmReview

> GetMatch200ResponseConfirmedPointsInner confirmReview(id, rvid, idempotencyKey, confirmReviewRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { ConfirmReviewOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // string
    rvid: rvid_example,
    // string
    idempotencyKey: idempotencyKey_example,
    // ConfirmReviewRequest (optional)
    confirmReviewRequest: ...,
  } satisfies ConfirmReviewOperationRequest;

  try {
    const data = await api.confirmReview(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **rvid** | `string` |  | [Defaults to `undefined`] |
| **idempotencyKey** | `string` |  | [Defaults to `undefined`] |
| **confirmReviewRequest** | [ConfirmReviewRequest](ConfirmReviewRequest.md) |  | [Optional] |

### Return type

[**GetMatch200ResponseConfirmedPointsInner**](GetMatch200ResponseConfirmedPointsInner.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | confirmed point |  -  |
| **409** | confirmation conflict |  -  |
| **422** | invalid confirmation |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createAnalysis

> CreateAnalysis200Response createAnalysis(idempotencyKey, createAnalysisRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { CreateAnalysisOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    idempotencyKey: idempotencyKey_example,
    // CreateAnalysisRequest (optional)
    createAnalysisRequest: ...,
  } satisfies CreateAnalysisOperationRequest;

  try {
    const data = await api.createAnalysis(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **idempotencyKey** | `string` |  | [Defaults to `undefined`] |
| **createAnalysisRequest** | [CreateAnalysisRequest](CreateAnalysisRequest.md) |  | [Optional] |

### Return type

[**CreateAnalysis200Response**](CreateAnalysis200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | existing run past queued or terminal; nothing dispatched |  -  |
| **202** | new dispatch or an existing run still queued |  -  |
| **403** | read-only developer keys cannot dispatch analyses |  -  |
| **429** | session has reached its analysis run limit |  -  |
| **503** | Modal rejected the dispatch |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createChallenge

> CreateChallenge201Response createChallenge(idempotencyKey, createChallengeRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { CreateChallengeOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    idempotencyKey: idempotencyKey_example,
    // CreateChallengeRequest (optional)
    createChallengeRequest: ...,
  } satisfies CreateChallengeOperationRequest;

  try {
    const data = await api.createChallenge(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **idempotencyKey** | `string` |  | [Defaults to `undefined`] |
| **createChallengeRequest** | [CreateChallengeRequest](CreateChallengeRequest.md) |  | [Optional] |

### Return type

[**CreateChallenge201Response**](CreateChallenge201Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | challenge created |  -  |
| **401** | missing or invalid token |  -  |
| **404** | challenge or match not visible to the caller |  -  |
| **409** | conflict with the stored challenge |  -  |
| **422** | invalid request |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createChallengeEntry

> CreateChallenge201Response createChallengeEntry(id, createChallengeEntryRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { CreateChallengeEntryOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // CreateChallengeEntryRequest (optional)
    createChallengeEntryRequest: ...,
  } satisfies CreateChallengeEntryOperationRequest;

  try {
    const data = await api.createChallengeEntry(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **createChallengeEntryRequest** | [CreateChallengeEntryRequest](CreateChallengeEntryRequest.md) |  | [Optional] |

### Return type

[**CreateChallenge201Response**](CreateChallenge201Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | the caller\&#39;s run is stored |  -  |
| **401** | missing or invalid token |  -  |
| **404** | challenge or match not visible to the caller |  -  |
| **409** | conflict with the stored challenge |  -  |
| **422** | invalid request |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createCorrection

> GetMatch200ResponseCorrectionsInner createCorrection(id, idempotencyKey, createCorrectionRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { CreateCorrectionOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // string
    idempotencyKey: idempotencyKey_example,
    // CreateCorrectionRequest (optional)
    createCorrectionRequest: ...,
  } satisfies CreateCorrectionOperationRequest;

  try {
    const data = await api.createCorrection(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **idempotencyKey** | `string` |  | [Defaults to `undefined`] |
| **createCorrectionRequest** | [CreateCorrectionRequest](CreateCorrectionRequest.md) |  | [Optional] |

### Return type

[**GetMatch200ResponseCorrectionsInner**](GetMatch200ResponseCorrectionsInner.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | correction appended with the resolved player actor |  -  |
| **404** | match not visible to the caller |  -  |
| **409** | Idempotency-Key was used with a different request |  -  |
| **422** | invalid request |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createDeveloperKey

> createDeveloperKey(idempotencyKey)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { CreateDeveloperKeyRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    idempotencyKey: idempotencyKey_example,
  } satisfies CreateDeveloperKeyRequest;

  try {
    const data = await api.createDeveloperKey(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **idempotencyKey** | `string` |  | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | pending Person 4: no vendored response schema; plaintext key is returned only once |  -  |
| **401** | missing or invalid authentication |  -  |
| **403** | developer keys cannot create keys |  -  |
| **409** | idempotency key was reused for a different request |  -  |
| **422** | request validation failed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createSession

> CreateSession201Response createSession(idempotencyKey, createSessionRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { CreateSessionOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    idempotencyKey: idempotencyKey_example,
    // CreateSessionRequest (optional)
    createSessionRequest: ...,
  } satisfies CreateSessionOperationRequest;

  try {
    const data = await api.createSession(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **idempotencyKey** | `string` |  | [Defaults to `undefined`] |
| **createSessionRequest** | [CreateSessionRequest](CreateSessionRequest.md) |  | [Optional] |

### Return type

[**CreateSession201Response**](CreateSession201Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | session and match created with the selected upload mode |  -  |
| **422** | invalid request |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createUploadParts

> CreateUploadParts200Response createUploadParts(id, createUploadPartsRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { CreateUploadPartsOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // CreateUploadPartsRequest (optional)
    createUploadPartsRequest: ...,
  } satisfies CreateUploadPartsOperationRequest;

  try {
    const data = await api.createUploadParts(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **createUploadPartsRequest** | [CreateUploadPartsRequest](CreateUploadPartsRequest.md) |  | [Optional] |

### Return type

[**CreateUploadParts200Response**](CreateUploadParts200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | presigned PUT URLs (600 s), in the order requested |  -  |
| **429** | part URL allowance exceeded; retry after issued URLs expire |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## deleteMatch

> deleteMatch(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { DeleteMatchRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies DeleteMatchRequest;

  try {
    const data = await api.deleteMatch(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | Match rows are deleted; recorded R2 objects are removed asynchronously after commit. |  -  |
| **404** | match not owned by the caller |  -  |
| **409** | an analysis is in progress |  -  |
| **500** | match deletion could not be completed |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getAnalysis

> GetAnalysis200Response getAnalysis(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { GetAnalysisRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies GetAnalysisRequest;

  try {
    const data = await api.getAnalysis(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |

### Return type

[**GetAnalysis200Response**](GetAnalysis200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | analysis |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getArtifact

> GetArtifact200Response getArtifact(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { GetArtifactRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies GetArtifactRequest;

  try {
    const data = await api.getArtifact(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |

### Return type

[**GetArtifact200Response**](GetArtifact200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | pending Person 4: signed artifact URL, expires in 300 seconds |  -  |
| **404** | artifact not found or not shared with the caller |  -  |
| **429** | daily download URL limit reached |  -  |
| **503** | R2 usage could not be recorded |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getCapabilities

> GetCapabilities200Response getCapabilities()



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { GetCapabilitiesRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  try {
    const data = await api.getCapabilities();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**GetCapabilities200Response**](GetCapabilities200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | capabilities.v1 |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getChallenge

> CreateChallenge201Response getChallenge(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { GetChallengeRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies GetChallengeRequest;

  try {
    const data = await api.getChallenge(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |

### Return type

[**CreateChallenge201Response**](CreateChallenge201Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | challenge as the caller may see it |  -  |
| **401** | missing or invalid token |  -  |
| **404** | challenge or match not visible to the caller |  -  |
| **409** | conflict with the stored challenge |  -  |
| **422** | invalid request |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getHealth

> GetHealth200Response getHealth()



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { GetHealthRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  try {
    const data = await api.getHealth();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**GetHealth200Response**](GetHealth200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | liveness |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getMatch

> GetMatch200Response getMatch(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { GetMatchRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies GetMatchRequest;

  try {
    const data = await api.getMatch(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |

### Return type

[**GetMatch200Response**](GetMatch200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | match.v1 |  -  |
| **404** | match not visible |  -  |
| **409** | analysis not complete |  -  |
| **500** | stored match failed contract validation |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getRally

> GetRally200Response getRally(id, rid)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { GetRallyRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // string
    rid: rid_example,
  } satisfies GetRallyRequest;

  try {
    const data = await api.getRally(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **rid** | `string` |  | [Defaults to `undefined`] |

### Return type

[**GetRally200Response**](GetRally200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | rally.detail.v1 |  -  |
| **404** | rally not visible |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getSession

> GetSession200Response getSession(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { GetSessionRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies GetSessionRequest;

  try {
    const data = await api.getSession(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |

### Return type

[**GetSession200Response**](GetSession200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | session |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getShotComparisons

> GetShotComparisons200Response getShotComparisons(mid, shotId, scope, limit)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { GetShotComparisonsRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    mid: mid_example,
    // string
    shotId: shotId_example,
    // 'own' | 'shared' | 'reference'
    scope: scope_example,
    // number (optional)
    limit: 56,
  } satisfies GetShotComparisonsRequest;

  try {
    const data = await api.getShotComparisons(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **mid** | `string` |  | [Defaults to `undefined`] |
| **shotId** | `string` |  | [Defaults to `undefined`] |
| **scope** | `own`, `shared`, `reference` |  | [Defaults to `undefined`] [Enum: own, shared, reference] |
| **limit** | `number` |  | [Optional] [Defaults to `3`] |

### Return type

[**GetShotComparisons200Response**](GetShotComparisons200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | ranked, viewable shot comparisons |  -  |
| **404** | match or shot not found for the caller |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listAnalysisEvents

> ListAnalysisEvents200Response listAnalysisEvents(id, afterSeq, limit)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { ListAnalysisEventsRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // number (optional)
    afterSeq: 56,
    // number (optional)
    limit: 56,
  } satisfies ListAnalysisEventsRequest;

  try {
    const data = await api.listAnalysisEvents(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **afterSeq** | `number` |  | [Optional] [Defaults to `0`] |
| **limit** | `number` |  | [Optional] [Defaults to `100`] |

### Return type

[**ListAnalysisEvents200Response**](ListAnalysisEvents200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | events after the cursor |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listFriends

> ListFriends200Response listFriends()



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { ListFriendsRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  try {
    const data = await api.listFriends();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**ListFriends200Response**](ListFriends200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | people the caller has exchanged challenges with |  -  |
| **401** | missing or invalid token |  -  |
| **404** | challenge or match not visible to the caller |  -  |
| **409** | conflict with the stored challenge |  -  |
| **422** | invalid request |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listMatchMoments

> ListMatchMoments200Response listMatchMoments(id, cursor, limit, tag)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { ListMatchMomentsRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // string (optional)
    cursor: cursor_example,
    // number (optional)
    limit: 56,
    // string (optional)
    tag: tag_example,
  } satisfies ListMatchMomentsRequest;

  try {
    const data = await api.listMatchMoments(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **cursor** | `string` |  | [Optional] [Defaults to `undefined`] |
| **limit** | `number` |  | [Optional] [Defaults to `20`] |
| **tag** | `string` |  | [Optional] [Defaults to `undefined`] |

### Return type

[**ListMatchMoments200Response**](ListMatchMoments200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | moments.page.v1 |  -  |
| **422** | invalid query or cursor |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listMatches

> ListMatches200Response listMatches(cursor, limit)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { ListMatchesRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string (optional)
    cursor: cursor_example,
    // number (optional)
    limit: 56,
  } satisfies ListMatchesRequest;

  try {
    const data = await api.listMatches(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **cursor** | `string` |  | [Optional] [Defaults to `undefined`] |
| **limit** | `number` |  | [Optional] [Defaults to `20`] |

### Return type

[**ListMatches200Response**](ListMatches200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | matches.page.v1 |  -  |
| **422** | invalid query or cursor |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listMoments

> ListMatchMoments200Response listMoments(cursor, limit, tag, matchId)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { ListMomentsRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string (optional)
    cursor: cursor_example,
    // number (optional)
    limit: 56,
    // string (optional)
    tag: tag_example,
    // string (optional)
    matchId: matchId_example,
  } satisfies ListMomentsRequest;

  try {
    const data = await api.listMoments(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **cursor** | `string` |  | [Optional] [Defaults to `undefined`] |
| **limit** | `number` |  | [Optional] [Defaults to `20`] |
| **tag** | `string` |  | [Optional] [Defaults to `undefined`] |
| **matchId** | `string` |  | [Optional] [Defaults to `undefined`] |

### Return type

[**ListMatchMoments200Response**](ListMatchMoments200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | moments.page.v1 |  -  |
| **422** | invalid query or cursor |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listRallies

> ListRallies200Response listRallies(id, cursor, limit)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { ListRalliesRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // string (optional)
    cursor: cursor_example,
    // number (optional)
    limit: 56,
  } satisfies ListRalliesRequest;

  try {
    const data = await api.listRallies(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **cursor** | `string` |  | [Optional] [Defaults to `undefined`] |
| **limit** | `number` |  | [Optional] [Defaults to `20`] |

### Return type

[**ListRallies200Response**](ListRallies200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | rallies.page.v1 |  -  |
| **422** | invalid query or cursor |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## registerSegment

> RegisterSegment200Response registerSegment(id, registerSegmentRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { RegisterSegmentOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // RegisterSegmentRequest (optional)
    registerSegmentRequest: ...,
  } satisfies RegisterSegmentOperationRequest;

  try {
    const data = await api.registerSegment(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |
| **registerSegmentRequest** | [RegisterSegmentRequest](RegisterSegmentRequest.md) |  | [Optional] |

### Return type

[**RegisterSegment200Response**](RegisterSegment200Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | segment registered with a presigned PUT URL |  -  |
| **409** | sequence gap or conflicting segment registration |  -  |
| **413** | segment bytes exceed the G1 cap |  -  |
| **429** | segment URL or storage budget reached |  -  |
| **503** | uploads paused or usage could not be recorded |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## revokeDeveloperKey

> revokeDeveloperKey(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { RevokeDeveloperKeyRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies RevokeDeveloperKeyRequest;

  try {
    const data = await api.revokeDeveloperKey(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | `string` |  | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | developer key revoked |  -  |
| **401** | missing or invalid authentication |  -  |
| **403** | developer keys cannot revoke keys |  -  |
| **404** | developer key not found or not owned by the caller |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

