# DefaultApi

All URIs are relative to *https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**v1AnalysesIdEventsGet**](DefaultApi.md#v1analysesideventsget) | **GET** /v1/analyses/{id}/events |  |
| [**v1AnalysesIdGet**](DefaultApi.md#v1analysesidget) | **GET** /v1/analyses/{id} |  |
| [**v1AnalysesPost**](DefaultApi.md#v1analysespostoperation) | **POST** /v1/analyses |  |
| [**v1ArtifactsIdGet**](DefaultApi.md#v1artifactsidget) | **GET** /v1/artifacts/{id} |  |
| [**v1HealthGet**](DefaultApi.md#v1healthget) | **GET** /v1/health |  |
| [**v1MatchesIdGet**](DefaultApi.md#v1matchesidget) | **GET** /v1/matches/{id} |  |
| [**v1MatchesIdReviewsRvidConfirmPost**](DefaultApi.md#v1matchesidreviewsrvidconfirmpostoperation) | **POST** /v1/matches/{id}/reviews/{rvid}/confirm |  |
| [**v1SessionsIdGet**](DefaultApi.md#v1sessionsidget) | **GET** /v1/sessions/{id} |  |
| [**v1SessionsIdUploadAbortPost**](DefaultApi.md#v1sessionsiduploadabortpost) | **POST** /v1/sessions/{id}/upload/abort |  |
| [**v1SessionsIdUploadCompletePost**](DefaultApi.md#v1sessionsiduploadcompletepostoperation) | **POST** /v1/sessions/{id}/upload/complete |  |
| [**v1SessionsIdUploadPartsPost**](DefaultApi.md#v1sessionsiduploadpartspostoperation) | **POST** /v1/sessions/{id}/upload/parts |  |
| [**v1SessionsPost**](DefaultApi.md#v1sessionspostoperation) | **POST** /v1/sessions |  |



## v1AnalysesIdEventsGet

> V1AnalysesIdEventsGet200Response v1AnalysesIdEventsGet(id, afterSeq, limit)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1AnalysesIdEventsGetRequest } from '@elevate/sdk';

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
  } satisfies V1AnalysesIdEventsGetRequest;

  try {
    const data = await api.v1AnalysesIdEventsGet(body);
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

[**V1AnalysesIdEventsGet200Response**](V1AnalysesIdEventsGet200Response.md)

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


## v1AnalysesIdGet

> V1AnalysesIdGet200Response v1AnalysesIdGet(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1AnalysesIdGetRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies V1AnalysesIdGetRequest;

  try {
    const data = await api.v1AnalysesIdGet(body);
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

[**V1AnalysesIdGet200Response**](V1AnalysesIdGet200Response.md)

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


## v1AnalysesPost

> V1AnalysesPost200Response v1AnalysesPost(idempotencyKey, v1AnalysesPostRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1AnalysesPostOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    idempotencyKey: idempotencyKey_example,
    // V1AnalysesPostRequest (optional)
    v1AnalysesPostRequest: ...,
  } satisfies V1AnalysesPostOperationRequest;

  try {
    const data = await api.v1AnalysesPost(body);
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
| **v1AnalysesPostRequest** | [V1AnalysesPostRequest](V1AnalysesPostRequest.md) |  | [Optional] |

### Return type

[**V1AnalysesPost200Response**](V1AnalysesPost200Response.md)

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
| **429** | session has reached its analysis run limit |  -  |
| **503** | Modal rejected the dispatch |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## v1ArtifactsIdGet

> V1ArtifactsIdGet200Response v1ArtifactsIdGet(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1ArtifactsIdGetRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies V1ArtifactsIdGetRequest;

  try {
    const data = await api.v1ArtifactsIdGet(body);
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

[**V1ArtifactsIdGet200Response**](V1ArtifactsIdGet200Response.md)

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


## v1HealthGet

> V1HealthGet200Response v1HealthGet()



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1HealthGetRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  try {
    const data = await api.v1HealthGet();
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

[**V1HealthGet200Response**](V1HealthGet200Response.md)

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


## v1MatchesIdGet

> V1MatchesIdGet200Response v1MatchesIdGet(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1MatchesIdGetRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies V1MatchesIdGetRequest;

  try {
    const data = await api.v1MatchesIdGet(body);
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

[**V1MatchesIdGet200Response**](V1MatchesIdGet200Response.md)

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


## v1MatchesIdReviewsRvidConfirmPost

> V1MatchesIdGet200ResponseConfirmedPointsInner v1MatchesIdReviewsRvidConfirmPost(id, rvid, idempotencyKey, v1MatchesIdReviewsRvidConfirmPostRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1MatchesIdReviewsRvidConfirmPostOperationRequest } from '@elevate/sdk';

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
    // V1MatchesIdReviewsRvidConfirmPostRequest (optional)
    v1MatchesIdReviewsRvidConfirmPostRequest: ...,
  } satisfies V1MatchesIdReviewsRvidConfirmPostOperationRequest;

  try {
    const data = await api.v1MatchesIdReviewsRvidConfirmPost(body);
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
| **v1MatchesIdReviewsRvidConfirmPostRequest** | [V1MatchesIdReviewsRvidConfirmPostRequest](V1MatchesIdReviewsRvidConfirmPostRequest.md) |  | [Optional] |

### Return type

[**V1MatchesIdGet200ResponseConfirmedPointsInner**](V1MatchesIdGet200ResponseConfirmedPointsInner.md)

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


## v1SessionsIdGet

> V1SessionsIdGet200Response v1SessionsIdGet(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1SessionsIdGetRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies V1SessionsIdGetRequest;

  try {
    const data = await api.v1SessionsIdGet(body);
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

[**V1SessionsIdGet200Response**](V1SessionsIdGet200Response.md)

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


## v1SessionsIdUploadAbortPost

> V1SessionsIdUploadAbortPost200Response v1SessionsIdUploadAbortPost(id)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1SessionsIdUploadAbortPostRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies V1SessionsIdUploadAbortPostRequest;

  try {
    const data = await api.v1SessionsIdUploadAbortPost(body);
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

[**V1SessionsIdUploadAbortPost200Response**](V1SessionsIdUploadAbortPost200Response.md)

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


## v1SessionsIdUploadCompletePost

> V1SessionsIdUploadCompletePost200Response v1SessionsIdUploadCompletePost(id, v1SessionsIdUploadCompletePostRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1SessionsIdUploadCompletePostOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // V1SessionsIdUploadCompletePostRequest (optional)
    v1SessionsIdUploadCompletePostRequest: ...,
  } satisfies V1SessionsIdUploadCompletePostOperationRequest;

  try {
    const data = await api.v1SessionsIdUploadCompletePost(body);
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
| **v1SessionsIdUploadCompletePostRequest** | [V1SessionsIdUploadCompletePostRequest](V1SessionsIdUploadCompletePostRequest.md) |  | [Optional] |

### Return type

[**V1SessionsIdUploadCompletePost200Response**](V1SessionsIdUploadCompletePost200Response.md)

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


## v1SessionsIdUploadPartsPost

> V1SessionsIdUploadPartsPost200Response v1SessionsIdUploadPartsPost(id, v1SessionsIdUploadPartsPostRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1SessionsIdUploadPartsPostOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // V1SessionsIdUploadPartsPostRequest (optional)
    v1SessionsIdUploadPartsPostRequest: ...,
  } satisfies V1SessionsIdUploadPartsPostOperationRequest;

  try {
    const data = await api.v1SessionsIdUploadPartsPost(body);
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
| **v1SessionsIdUploadPartsPostRequest** | [V1SessionsIdUploadPartsPostRequest](V1SessionsIdUploadPartsPostRequest.md) |  | [Optional] |

### Return type

[**V1SessionsIdUploadPartsPost200Response**](V1SessionsIdUploadPartsPost200Response.md)

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


## v1SessionsPost

> V1SessionsPost201Response v1SessionsPost(idempotencyKey, v1SessionsPostRequest)



### Example

```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1SessionsPostOperationRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    idempotencyKey: idempotencyKey_example,
    // V1SessionsPostRequest (optional)
    v1SessionsPostRequest: ...,
  } satisfies V1SessionsPostOperationRequest;

  try {
    const data = await api.v1SessionsPost(body);
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
| **v1SessionsPostRequest** | [V1SessionsPostRequest](V1SessionsPostRequest.md) |  | [Optional] |

### Return type

[**V1SessionsPost201Response**](V1SessionsPost201Response.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | session, match and R2 multipart upload created |  -  |
| **422** | invalid request or segments upload is not available |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

