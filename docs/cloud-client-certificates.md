# Client certificates for cloud accounts

For an HTTPS server that requires mutual TLS, install its client certificate and private key
in Android's credential settings. In ReFra, choose **Select certificate** below the server URL
during account setup, or open an existing account's **Networking** settings. The main URL,
local-network URL, and each additional external URL have independent selections. **Remove
selection** stops using the certificate for that URL; it does not delete the Android credential.

Setup collects networking settings before testing credentials, so a local URL's certificate
can be selected before the local connection is tested. Nextcloud's browser sign-in may prompt
for its own certificate selection; ReFra's selection covers its login requests and polling.

Only the Android KeyChain alias is saved in the account database. Private keys stay in Android's
credential store. Certificate aliases and keys are excluded from portable configuration backups;
select the certificate again after restoring an account. An expired, removed, or inaccessible
credential must be replaced or selected again.

## Transport contract

`CloudTlsClient` wraps an existing OkHttp client for one account's effective base URL. It keeps
the base client's server trust policy, cache, interceptors, and timeouts. Each certificate profile
has its own TLS context and connection pool. The key manager only supplies the selected identity
to the effective URL's HTTPS host and port. Redirects to another origin do not receive it, and
HTTP/2 cross-host connection coalescing is disabled for certificate profiles.

TLS authenticates a connection, before an HTTP path is sent. Different configured paths may
have independent selections: the effective configured base URL determines the connection's
identity, which also applies to that server's API and media paths. Editing an address does not
automatically transfer its certificate selection to the new URL. Equivalent casing/default ports
and a trailing slash are normalized. Existing local/external URL switching behavior is unchanged.

Provider API clients and `RemoteMediaProvider.mediaHttpClient` use the same profile. Any new
HTTP provider must apply `CloudTlsClient` both to its API client and to that media hook. Media
consumers use the hook for previews, playback, downloads, offline pinning, and frame extraction.

Android's [KeyChain API](https://developer.android.com/reference/android/security/KeyChain)
provides the system picker and private-key access. Key and certificate lookups run on the TLS
worker thread; selection validation runs on an IO dispatcher.
