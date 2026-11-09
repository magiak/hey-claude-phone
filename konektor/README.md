# Vlastní nástroje pro Clauda přes MCP

Aplikace Claude **neumí volat lokální MCP servery**. Vlastní konektory volá z cloudu Anthropicu, takže potřebují veřejnou adresu na internetu. Server v telefonu ani v domácí síti tak napřímo nejde.

Tady je MCP server přímo v telefonu a veřejnou adresu mu dělá malý průchod (relay) na Cloudflare. Telefon k němu drží odchozí WebSocket, takže doma není potřeba otevírat porty ani nechávat běžet počítač. V hovoru pak Claude může měnit hlasitost telefonu, tisknout a skenovat na tiskárně v Wi-Fi a měnit domovskou obrazovku. Přidat jde jakýkoliv další nástroj.

```
Claude (cloud) --HTTPS /mcp--> průchod (Cloudflare Worker) <--WebSocket /phone/<klíč>-- telefon
                                                                     telefon --IPP / eSCL--> tiskárna
```

Celá část je volitelná: bez `konektor.url` se aplikace sestaví a chová jako dřív.

## Kde je co

- `konektor/src/index.ts`: průchod. Mluví MCP Streamable HTTP s obyčejnými JSON odpověďmi (`initialize`, `ping`, `tools/list`, `tools/call`), volání nástrojů posílá telefonu a čeká na odpověď (nejdéle 55 s). Seznam nástrojů si pamatuje, takže ho claude.ai uvidí, i když je telefon offline. Jedno nasazení = jeden telefon.
- `android/app/src/main/java/cz/magiak/asistent/mcp/`: MCP server v telefonu.
  - `Konektor.kt` drží spojení, po připojení pošle seznam nástrojů (`tools/announce`), každých 30 s pošle `ping` a po výpadku se znovu připojí.
  - `Tools.kt` jsou nástroje a jejich popisy pro Clauda.
  - `Printer.kt` a `Ipp.kt` tisknou přes IPP a skenují přes eSCL (AirScan).

Jiný průchod než Cloudflare stačí, když umí totéž: jednu HTTPS adresu pro Clauda a jeden WebSocket pro telefon.

## Nasazení na Cloudflare

Potřebuješ Node.js a účet na Cloudflare (stačí Free plán).

```bash
cd konektor
npm ci
npx wrangler login
npx wrangler deploy
```

Ve Windows PowerShellu místo `npx` piš `npx.cmd`, pokud `npx.ps1` blokuje ExecutionPolicy. První nasazení založí subdoménu `workers.dev` a vypíše adresu průchodu.

Vygeneruj klíč a ulož ho do Cloudflare:

```bash
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
npx wrangler secret put SECRET
```

Dokud klíč není nastavený, průchod na všechno odpovídá 404.

## Telefon

Do `android/local.properties` (git ho ignoruje) přidej:

```properties
konektor.url=wss://<adresa-průchodu>/phone/<klíč>
printer.host=<IP tiskárny>
```

Pak `./gradlew installDebug` a aplikaci spusť přes `adb shell am start -n cz.magiak.asistent/.MainActivity`. Nikdy `am force-stop`: vypne službu přístupnosti a musí se znovu zapnout ručně. Když spojení nejde, displej napíše „Konektor je odpojený“.

## Konektor na claude.ai

1. Na claude.ai otevři Nastavení, Connectors a přidej vlastní konektor (Add custom connector). Na Free plánu jde jen jeden.
2. Adresa `https://<adresa-průchodu>/mcp`, přihlášení „No sign-in“.
3. V hlavičkách požadavku (Request headers) přidej `X-Api-Key` s klíčem. Hlavičku claude.ai neukazuje, adresu ano, proto klíč není v adrese.
4. Kontrola před uložením hlásí 404, protože ještě nezná hlavičku. Pokračuj („Continue anyway“).
5. Konektor ukáže nástroje. U každého jde nastavit, jestli smí běžet sám, nebo se ptá.

## Vlastní nástroj

V `Tools.kt` přidej nástroj do `list()` (jméno, popis, JSON schéma parametrů) a do `call()`. Popis je instrukce pro Clauda, napiš ho jako pokyn. Po instalaci se telefon znovu připojí a nový seznam pošle sám. Popisy a `INSTRUCTIONS` v `index.ts` jsou česky, pro jiný jazyk je přepiš.

## Bezpečnost

- **Klíč je heslo.** Kdo ho má, ovládá tvůj telefon a tiskárnu. Nedávej ho do gitu, do chatu ani na screenshot. Výměna: nový `wrangler secret put SECRET`, nový `konektor.url`, znovu `installDebug` a nová hlavička v konektoru.
- Špatný nebo chybějící klíč dostane 404, ne 401: na 401 by Claude začal hledat OAuth přihlášení.
- Adresu průchodu nezveřejňuj. Bez klíče nic neudělá, ale požadavky z cizí strany ti ukrajují denní limit Free plánu.
- Tiskárny mluví nešifrovaným HTTP, proto `network_security_config.xml` povoluje HTTP pro celou aplikaci.
- Text, který Claude čte (třeba naskenovaný papír), mu může něco přikázat. Tisk proto jde ve dvou krocích: `prepare_print` řekne počet stran a stav inkoustu a `print` má Claude volat až po souhlasu. Na tisk můžeš navíc zapnout v konektoru, ať se ptá.

## Co ověřené není

- Hlasitost v hlasovém hovoru funguje. Tisk a sken na skutečné tiskárně zatím neproběhly.
- Jak vypadá potvrzení nástroje v hlasovém režimu a jestli tam dojde obrázek ze skenu.
- Jak dlouho Claude na jeden nástroj čeká.
