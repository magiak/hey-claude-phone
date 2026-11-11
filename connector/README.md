# Custom tools for Claude over MCP

The Claude app **can't call local MCP servers**. It calls custom connectors from Anthropic's cloud, so they need a public internet address. A server in the phone or in the home network can't be reached directly.

Here the MCP server is right in the phone, and a small relay on Cloudflare gives it a public address. The phone keeps an outgoing WebSocket to it, so at home there's no need to open ports or keep a computer running. During a call Claude can then change the phone's volume, print and scan on a Wi-Fi printer and change the home screen. Any other tool can be added.

```
Claude (cloud) --HTTPS /mcp--> relay (Cloudflare Worker) <--WebSocket /phone/<key>-- phone
                                                                     phone --IPP / eSCL--> printer
```

The whole part is optional: without `connector.url` the app builds and behaves as before.

## What is where

- `connector/src/index.ts`: the relay. It speaks MCP Streamable HTTP with plain JSON responses (`initialize`, `ping`, `tools/list`, `tools/call`), forwards tool calls to the phone and waits for the reply (55 s at most). It remembers the tool list, so claude.ai sees it even when the phone is offline. One deployment = one phone.
- `android/app/src/main/java/cz/magiak/asistent/mcp/`: the MCP server in the phone.
  - `Connector.kt` holds the connection, sends the tool list after connecting (`tools/announce`), sends a `ping` every 30 s and reconnects after a drop.
  - `Tools.kt` has the tools and their descriptions for Claude.
  - `Printer.kt` and `Ipp.kt` print over IPP and scan over eSCL (AirScan).

A relay other than Cloudflare is fine if it does the same: one HTTPS address for Claude and one WebSocket for the phone.

## Deploying to Cloudflare

You need Node.js and a Cloudflare account (the Free plan is enough).

```bash
cd connector
npm ci
npx wrangler login
npx wrangler deploy
```

In Windows PowerShell write `npx.cmd` instead of `npx` if ExecutionPolicy blocks `npx.ps1`. The first deploy creates a `workers.dev` subdomain and prints the relay address.

Generate a key and store it in Cloudflare:

```bash
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
npx wrangler secret put SECRET
```

Until the key is set, the relay answers 404 to everything.

## Phone

Add to `android/local.properties` (git ignores it):

```properties
connector.url=wss://<relay-address>/phone/<key>
printer.host=<printer IP>
```

Then `./gradlew installDebug` and start the app with `adb shell am start -n cz.magiak.asistent/.MainActivity`. Never `am force-stop`: it turns off the accessibility service and it has to be turned on again by hand. If the connection fails, the display says the connector is disconnected.

## Connector on claude.ai

1. On claude.ai open Settings, Connectors and add a custom connector (Add custom connector). The Free plan allows only one.
2. Address `https://<relay-address>/mcp`, authentication "No sign-in".
3. In the request headers add `X-Api-Key` with the key. claude.ai doesn't show the header but does show the address, which is why the key isn't in the address.
4. The check before saving reports 404, because it doesn't know the header yet. Continue ("Continue anyway").
5. The connector shows the tools. For each you can set whether it may run on its own or asks first.

## Custom tool

In `Tools.kt` add the tool to `list()` (name, description, JSON schema of parameters) and to `call()`. The description is an instruction for Claude, write it as a directive. After installing, the phone reconnects and sends the new list by itself. The descriptions and `INSTRUCTIONS` in `index.ts` are in English; Claude answers in the user's language, and the date on the home screen follows the phone's language.

## Security

- **The key is a password.** Whoever has it controls your phone and printer. Don't put it in git, in a chat or in a screenshot. To rotate: a new `wrangler secret put SECRET`, a new `connector.url`, `installDebug` again and a new header in the connector.
- A wrong or missing key gets 404, not 401: on 401 Claude would start looking for an OAuth sign-in.
- Don't publish the relay address. Without the key it does nothing, but requests from outside eat into the Free plan's daily limit.
- Printers speak unencrypted HTTP, so `network_security_config.xml` allows HTTP for the whole app.
- Text that Claude reads (say, a scanned paper) can tell it to do something. So printing takes two steps: `prepare_print` reports the page count and ink level, and Claude should call `print` only after your consent. You can also make the connector ask before printing.

## What isn't verified

- Volume in a voice call works. Printing and scanning on a real printer haven't happened yet.
- What the tool confirmation looks like in voice mode and whether an image from a scan gets there.
- How long Claude waits for one tool.
