# Saimo TV — Android TV / Google TV

<p align="center">
  Saimo TV: <b>TV Box</b> · <a href="https://github.com/gabrielsaimo/Saimo-Cell-V2">Celular</a> · <a href="https://github.com/gabrielsaimo/SaimoWin">Windows</a> · <a href="https://github.com/gabrielsaimo/SaimoPlayer">Mac e catálogo</a> · <a href="https://github.com/gabrielsaimo/Saimo-TV">Site</a> · <a href="https://github.com/gabrielsaimo">todos os apps</a>
</p>

Canais ao vivo com guia de programação, filmes, séries, animes e doramas, feitos
para TV Box e controle remoto. Funciona do **Android 6** em diante (quem ainda
tem Android 5 fica na 2.0.2).

O APK sai no mesmo release dos outros apps (página de releases do repositório
[SaimoPlayer](https://github.com/gabrielsaimo/SaimoPlayer)).

**App sem listas:** o app não traz canal, filme, rádio nem tabela de guia
nenhuma (nem em código, nem em recursos). Só dois endereços-base, ativos de
fábrica e únicos em `Enderecos.kt` (`PADRAO_CANAIS` e `PADRAO_FILMES`); em
**Ajustes > Listas** dá para trocá-los (com teste de `catalogo.txt` /
`indice.txt`), **desativar** uma lista (campo em branco: estado vazio e nenhum
pedido de rede) ou **Restaurar padrão**. Os campos aceitam texto do celular pelo
Saimo Connect. O endereço é normalizado (`Enderecos.normalizar`).
- Endereço dos canais: `catalogo.txt`, `canais.txt`, `restritos.txt`,
  `radios.txt` e `guia.txt` (tabelas do guia: códigos meuguia/guiadetv, apelidos,
  feeds XMLTV, Pluto; ver `GuiaTabelas.kt`). Seção de cada canal = `categoria:`
  do catálogo; sem categoria, "Outros".
- Endereço dos filmes: espelho da pasta `vod/` (índices, fichas, destaques etc.).
- Na primeira execução desta versão os caches de listas antigos são apagados.

**Atualização:** o app lê `https://canais-saimo.pages.dev/versao.json`
(`arquivos.tvbox`) e oferece a versão na
tela. Só aceita download `https` de `github.com/<dono>/<repo>/releases/download/…`
(ou dos hosts de redirecionamento `objects.githubusercontent.com` e
`release-assets.githubusercontent.com`) e, se o `sha256` vier no JSON, recusa o
arquivo que não bater. Se o `versao.json` não abrir, vale como "sem atualização".

## O que tem

Fontes sem endereço à mostra: as listas de fontes (canais e filmes) mostram só
"Fonte N", o nome da fonte (ex.: "Temporada 3") e a qualidade; nunca o servidor
nem o endereço.

**Navegação**
- Abertura animada na partida a frio pelo launcher: o logo montado peça por
  peça (`res/raw/abertura_h.mp4`, e o 9:16 quando a tela está em pé), sem som,
  cobrindo a tela, com fade de 250 ms para o Início, que carrega por baixo.
  Qualquer tecla ou toque pula. Link `saimo://`, volta do segundo plano e
  rotação não repetem. A janela e a tela do sistema (Android 12+) abrem no
  mesmo azul (#0B0F2A), sem ícone. Se o vídeo falhar ou o primeiro quadro não
  vier em 1,5 s, a abertura sai na hora (`Abertura.kt`).
- Menu do topo em todas as telas — Início, Ao vivo, Filmes, Séries, Animes,
  Doramas, Favoritos, Buscar e Ajustes. Seta para cima chega nele de qualquer
  lugar; VOLTAR sempre leva à tela inicial.
- Tela inicial em fileiras: continuar assistindo, destaques, lançamentos,
  gêneros e canais no ar. O destaque do topo acompanha o cartão em foco.
  Quando o catálogo publica um trailer direto (MP4/HLS) para o título, ele
  começa sem som depois de 5 s de foco parado; se tocou e o foco ficou ali
  mais 15 s, a ficha abre. Sem trailer, nada abre sozinho (`Destaque.kt`).
- Rádios no formato da tela inicial: ouvidas por último, uma fileira por estilo
  e todas de A a Z, com o logo de cada emissora. A lista aparece na hora (a
  guardada ou a que vem no app) e se atualiza por trás. Tocando, a tela mostra
  o logo, o nome, o estilo e um equalizador; cima/baixo trocam de estação e
  esquerda/VOLTAR voltam às rádios.
- On Demand como a tela inicial: destaque, cartões das quatro categorias com
  colagem de capas, fileiras e o A a Z no fim.
- Eventos em fileiras por horário (ao vivo agora, em breve, hoje, amanhã), com
  os escudos, o placar ao vivo da ESPN e o canal de cada jogo. Além da agenda
  da embedtv, os jogos dos canais de streaming (CazéTV, Disney+, Prime,
  Paramount+, GOAT, ge tv, N Sports, SportyNet) vêm do monitor (`/v1/eventos`),
  sem repetir os que a embedtv já tem.
- Páginas de Filmes/Séries/Animes/Doramas com fileiras e um "Explorar" de A a Z
  com filtro de gênero e ordenação.
- Busca única (canais, filmes, séries, atores) com teclado na tela, voz,
  tolerância a erro de digitação e histórico.
- Ícones Material em tudo (nada de emoji, que vira quadrado em box antigo).
- Carrosséis estáveis: a capa não cresce no foco (o contorno ciano mostra onde
  está), andar para o lado nunca rola a tela, a seta para na ponta da fileira
  e só cima/baixo trocam de fileira — a fileira nova sobe sempre para o mesmo
  lugar. Capas com canto arredondado; logo PNG de canal sem a letra atrás
  (`Cartoes.kt`, `Inicio.Filas`).

**Canais ao vivo**
- **Minhas listas** (Ajustes > Minhas listas): a pessoa traz o **link** de uma
  lista `.m3u` comum (digitado no teclado da TV ou enviado pelo celular via
  Saimo Connect, como o "Endereço das listas") ou escolhe um **arquivo**
  (seletor do sistema, SAF; se a TV só tem o "stub" ou nenhum app de arquivos,
  o app lista os `.m3u`/`.m3u8`/`.m3` de Download e Documents para escolher com
  o controle; no Android 13+ essa lista alternativa não vê arquivos, aí vale o
  link). Os canais entram no Ao vivo na seção **Minhas listas** (depois das
  demais), com logo, ordenados por nome, e a Busca os encontra. Tudo fica só no
  aparelho: índice em SharedPreferences e um JSON de canais por lista em
  `files/minhas_listas/`. Até 10 listas; ativar/desativar, atualizar e remover
  (com confirmação). Lista de link atualiza sozinha ao abrir o app se passou de
  12 h (em segundo plano; se falhar, fica a guardada); lista de arquivo não tem
  link e "Atualizar" pede o arquivo de novo. O link nunca é mostrado nem
  registrado. Grupo ou nome adulto (`adult`, `xxx`, `porn`, `+18`...; "Adult
  Swim" não conta) fica restrito: só aparece depois do desbloqueio do controle
  parental, que não foi tocado (`Unlock` é o mesmo; sem lista restrita oficial o
  código nunca desbloqueia e eles ficam fora). Canal com nome igual a um do
  catálogo ganha o nome da lista entre parênteses. Sem nenhuma lista o app
  carrega exatamente como antes. Código: `LeitorM3u.kt` (leitor, Kotlin puro,
  testado contra os gabaritos em `LeitorM3uTest`), `MinhasListas.kt` (guardar,
  baixar, juntar aos canais), `MinhasListasTela.kt` (telas) e o ponto de junção
  em `MainActivity.reorder`/`BuscaActivity` (`MinhasListas.somar`).
- O vídeo abre espaço para os menus: com a lista de canais ele encolhe para a
  direita (abaixo do menu do topo, acima da prévia) e, com um painel lateral
  (fontes, opções, áudio), para a esquerda — inteiro, na proporção da tela e
  sem escurecer, com cantos arredondados (`Cantos.kt`). Só escala e posição,
  animadas pela GPU (`Palco.kt`).
- Lista com seções (TV Aberta, Esportes, Notícias…), prévia do programa do canal
  em foco e números fixos por canal.
- Segurar a seta acelera (1, 3, 10 canais por passo); CH+/CH− na lista pula de
  seção; o app abre no último canal e LAST volta ao anterior.
- Guia de programação, lembretes de programa e timer de sono.
- Troca de fonte automática quando uma cai, e manual pela lista.
- Série: o próximo episódio (automático, botão ou painel) começa pela mesma fonte do anterior: mesmo servidor, senão mesma posição; se não abrir, a troca automática segue normal.
- Ao vivo não pausa: PLAY/PAUSE do controle e do Assistant não param o canal.
- **Multi tela** (`MultiTela.kt`): dois canais lado a lado, em "Multi tela" nas
  opções (segurar OK ou MENU). A tela divide ao meio, a direita começa vazia e
  a lista abre para escolher o canal dela. Cada metade mostra no alto o logo,
  o número e o nome do canal. Só a metade ativa (contorno ciano)
  tem som, e é nela que a lista, as setas e os números sintonizam; cada uma
  troca de fonte sozinha quando a dela cai. Cada metade fica limitada a 720p
  (480p em aparelho de pouca memória) e o segundo player só existe enquanto a
  multi tela está aberta. Aparelho que não toca dois vídeos ao mesmo tempo
  avisa e volta a uma tela só. Ao sair, o player principal solta o
  decodificador da metade e recomeça em tela cheia, sem limite de qualidade
  (reaproveitado, alguns decodificadores desenhavam o quadro pequeno num
  canto). Não aparece na rádio nem no óculos. No
  celular e no tablet entra pelo botão Opções da barra, e tocar na outra
  metade passa o som e a lista para ela.

**Filmes e séries**
- No topo do player, com a barra de progresso, a logo do filme ou da série
  (a do TMDB, em português quando há) à esquerda do nome.
- Ficha com sinopse, elenco, temporadas e episódios, e "Mais como este".
- Player próprio: pula abertura e recapitulação com os tempos do
  [TheIntroDB](https://theintrodb.org), cartão do próximo episódio nos créditos,
  "ainda está assistindo?", continuar de onde parou.
- **Episódios** dentro do player (botão, ou seta para baixo com a barra
  escondida): faixa na parte de baixo com as temporadas em pílulas e os
  episódios em cartões com a imagem da cena (TMDB w300; sem ela, a imagem da
  série), quanto já foi visto e o selo "Assistindo". Abre no episódio no ar;
  esquerda/direita nas pílulas já troca a temporada, para baixo volta aos
  episódios, OK troca de episódio (no que está no ar só fecha), VOLTAR fecha.
- Botão **Fonte N**: escolhe entre todas as fontes (versão e servidor) sem
  perder o ponto. Fonte que não abre em 15 s, ou que "termina" nos primeiros
  segundos, cai para a seguinte sozinha.
- Áudio, legendas (tamanho, fundo, idioma) e qualidade. Filmes e séries ganham
  legendas do OpenSubtitles (pt-BR, pt-PT, inglês, espanhol) com ajuste de
  sincronia, no mesmo painel — sem chave nem cadastro.

**Óculos de VR e celular (modo toque)**
- O mesmo APK reconhece sozinho quando está num óculos Android (Meta Quest e
  Meta VR Glasses no Horizon OS, Pico, Vive Focus, Galaxy XR e demais Android
  XR) ou num celular/tablet, e liga o que falta para o toque (olhar e pinçar,
  no óculos) fazer tudo o que o D-pad faz: barra sobre o vídeo no ao vivo
  (lista, canal acima/abaixo, guia, fontes, anterior, número, opções), lista e
  teclado que fecham tocando fora, painéis com "Fechar", barra de tempo que se
  arrasta, ±10 s, botão Voltar nas telas (no óculos) e dicas de tela em
  linguagem de toque. O vídeo usa TextureView nesse modo.
- No celular e no tablet o app fica em tela cheia: as barras de navegação e
  de status somem em todas as telas e painéis (`Vr.telaCheia`); um deslize da
  borda mostra por um instante.
- O voltar preditivo do Android 13+ está desligado
  (`android:enableOnBackInvokedCallback="false"` no manifesto): com ele, o
  Android 16 deixa de entregar a tecla VOLTAR ao `onKeyDown`, e o Voltar da
  barra de toque (que simula a tecla) não fazia nada.
- No TV Box nada disso aparece. Para testar em qualquer aparelho:
  `adb shell am start -n br.com.saimo.tv/.EscolhaActivity --es saimo_vr sim`
  (`nao` desliga, `auto` volta à detecção).

**Google TV**
- "Continuar assistindo" do sistema (Watch Next), canal do app na tela inicial e
  busca do sistema. Links `saimo://assistir`, `saimo://titulo` e `saimo://canal`.
  No Google TV mais novo o sistema só mostra isso para apps parceiros.

**Nada de terceiros**: nenhum recurso depende de outro app instalado (o trailer
via YouTube saiu na 2.0 por isso).

## Controle

Ao vivo:

| Tecla | Ação |
|---|---|
| OK | mostra a programação do canal (rodapé) |
| OK com o rodapé na tela | escolhe a fonte |
| segurar OK, ou MENU | opções: áudio, fontes, favorito, timer |
| ↑ / ↓, CH+ / CH− | canal anterior / seguinte |
| ← | lista de canais; dentro dela, as seções |
| →, GUIA | programação; dentro da lista, as fontes do canal |
| LAST | volta ao canal anterior |
| 0–9 | número do canal |
| VOLTAR | fecha a lista |
| PLAY/PAUSE | nada: ao vivo sempre toca |

Na multi tela:

| Tecla | Ação |
|---|---|
| ← / → | escolhe a metade ativa (a que tem som) e mostra o canal dela |
| OK | programação do canal da metade ativa (o rodapé, sobre ela) |
| OK com o rodapé na tela | lista de canais da metade ativa |
| ↑ / ↓, CH+ / CH−, 0–9 | troca o canal da metade ativa |
| segurar OK, ou MENU | lista, sair da multi tela, tela inicial |
| VOLTAR | sai: o canal da metade ativa fica em tela cheia |

Filmes e séries: ← / → avançam e voltam (segurar acelera), OK mostra os
controles, ↓ leva aos botões, VOLTAR sai guardando o ponto.

O controle da Xiaomi não tem MENU nem números: tudo que depende deles tem
caminho alternativo (segurar OK, teclado na tela).

## Leve em aparelho de 1 GB

Um TV Box de 1 GB mata o app sem aviso quando a memória aperta. O que foi feito
para isso não acontecer:

- Guia XMLTV lido em fluxo, sem expressão regular por programa; o regex do
  Android copia a entrada para memória nativa, e isso derrubava o canal no ar.
- Cache do guia lido e gravado em fluxo (`JsonReader`/`JsonWriter`); em
  aparelho fraco a grade cobre 30 h e os feeds só completam canais sem guia.
- Buffer do vídeo com teto em bytes, cache de imagens de tamanho fixo e
  bitmaps RGB_565 em aparelho fraco.
- Arquivo de gêneros e índice de busca compactos, lidos em fluxo.
- O guia já na memória não é relido a cada abertura do Ao vivo, e só uma carga
  roda por vez (`Epg.load`); datas do XMLTV contadas à mão, sem `Calendar`.
- Filmes com buffer limitado (15–30 s e 32 MB no aparelho fraco; 96 MB nos
  outros), e o Ao vivo com teto de 96 MB também fora dele.
- Fora da tela, o Ao vivo para os relógios e, depois de 45 s, solta o fluxo e o
  decodificador; voltando, o canal recomeça sozinho. Com o app em segundo
  plano, `onTrimMemory` solta índices de busca, fichas, legendas e capas
  guardados (`Memoria.kt`, mapas com limite de itens); tudo volta do disco.
- Painel da rádio a 20 qps num ritmo só, com os gradientes reaproveitados.
- Listas de canais atualizam só o programa no ar a cada 20 s, com id fixo
  por canal; perfil de compilação antecipada (`baseline-prof.txt`).
- Catálogo baixado com ETag (no máximo uma conferência a cada 10 min), DNS do
  DoH guardado por 5 min, rede e certificados preparados fora da tela.

Medido no emulador de 1 GB (12/10/2026, antes → depois): Ao vivo abrindo em
5,3 s → 0,8 s; Programação em 1,8 s → 0,2 s; filme com 150 MB → 75 MB de
memória; rádio com 33% → 18% de CPU; multi tela com 32% → 20%.

Detecção de "pouca memória" em `Aparelho.kt` (isLowRamDevice ou até 1,5 GB).

## Estrutura

| Arquivo | Papel |
|---|---|
| `EscolhaActivity` | tela inicial (fileiras) |
| `Abertura` | vídeo de abertura na partida a frio |
| `MainActivity` | TV ao vivo |
| `MultiTela` | segunda metade da multi tela (player, troca de fonte) |
| `PlayerActivity`, `PainelEpisodios` | player de filmes e séries e a faixa de episódios |
| `FichaActivity`, `AtorActivity` | ficha do título e do ator |
| `PaginaActivity`, `GradeActivity` | páginas por tipo e grade A–Z |
| `BuscaActivity`, `BuscaDoSistema` | busca do app e do Google TV |
| `GuideActivity`, `Lembretes` | guia e lembretes |
| `AjustesActivity`, `Preferencias` | ajustes |
| `MenuGlobal` | menu do topo e fundo, herdados por `TelaComMenu` |
| `Epg`, `MeuGuia`, `GuiaDeTv` | guia de programação |
| `Vod`, `Titulos`, `Generos`, `Progresso` | acervo, resolução de título, fichas e progresso |
| `Pulos` | TheIntroDB |
| `Vr` | modo toque: detecção de óculos/celular e as peças de toque |
| `Legendas` | OpenSubtitles pelo id do TMDB (via `vod/imdb/`) |
| `ProximaNaTv`, `CanalNaTv`, `Links` | integração com o Google TV |
| `Remote`, `Catalog`, `FontesDesativadas` | lista de canais publicada |
| `Telemetria` | contagem anônima de uso (desligável nos Ajustes) |
| `Connect`, `ConnectHls`, `Sincronia` | Saimo Connect: controle pelo celular (porta 8999, `/info`, `/ws`) e o som no celular sincronizado (abaixo) |

## Telemetria por amostra

O monitor é cobrado por pedido, então só uma fatia fixa dos aparelhos fala com
ele (`Amostra`, em `Telemetria.kt`): FNV-1a 32 bits do `deviceId` em
minúsculas, dentro quando `hash % 10000 < amostraPct * 100` — a mesma conta do
`worker.js`. O percentual (padrão 5) vem de `/telemetria.json` (arquivo
estático, grátis), relido no máximo uma vez por dia e guardado; o `hello`
também pode atualizá-lo. Fora da amostra não sai hello, batida nem evento —
só `crash`. Dentro, batidas fora do relógio (pausa, troca de título, parada)
saem no máximo uma a cada 30 s; a última, ao ir para segundo plano, sempre.
A lista de servidores desligados (`/v1/fontes`) vale uma hora e fica guardada
entre aberturas; a agenda do monitor (`/v1/eventos`) só é pedida com a tela de
Eventos aberta, a cada 3 min (na hora ao abrir, se a guardada tem mais de
1 min). Teste: `AmostraTest`.

## Som no celular (Saimo Connect)

O celular conectado pode tocar o som de uma tela (ou das duas) junto com a
imagem da TV. Os dois abrem o canal cada um por conta própria, então precisam
de uma régua comum (`Sincronia`): a hora real do trecho quando a lista traz
`EXT-X-PROGRAM-DATE-TIME` ou é DASH; a régua contínua do proxy (abaixo)
quando não traz; e a posição no filme no VOD.

- `GET /hls?u=<lista>&r=<referer>&ua=<agente>` (`ConnectHls`): a TV baixa a
  lista com os cabeçalhos certos e devolve com endereços absolutos. Os
  pedaços o celular busca direto na origem.
- `Historico`: muitos canais guardam só 10 a 15 s na lista, e a TV costuma
  tocar mais atrás que isso; o trecho que ela mostrava já tinha saído da lista
  quando o celular pedia, e ele ficava pulando sem nunca chegar (som mudo).
  A TV lembra os trechos dos últimos 2 minutos e devolve a lista estendida.
  Sem marca de horário no canal, escreve uma régua contínua pela duração real
  de cada trecho (EXTINF), ancorada no relógio; listas de outros celulares ou
  de outra qualidade do mesmo canal reaproveitam a mesma âncora. A TV converte
  a própria posição (trecho que toca e quanto já tocou dele, contando para
  trás quando está antes do começo da lista) para essa régua.
- O `pong` devolve o `c0` do celular, que mede a ida e volta.
- Com o som no celular ligado, a TV manda a cada segundo
  `{t:"pos", wall, screens:[{idx, st, playing}]}`.

Medido no emulador (TV + tablet): fica dentro de ±40 ms por minutos seguidos,
com ou sem marca de horário no canal.

## Versões

Build com Gradle 9.8, Android Gradle Plugin 9.4 (Kotlin embutido, 2.4) e
Android 37. Player Media3 1.11, imagens Coil 3.6, rede OkHttp 5.5 com
DNS-over-HTTPS.

## Compilar

SDK, Gradle e emuladores ficam no SSD
(`/Volumes/SSD 1TB/DEV/AndroidDev`, com links em `~/Library/Android/sdk`).

```bash
export ANDROID_HOME="/Volumes/SSD 1TB/DEV/AndroidDev/sdk" JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O APK de release (R8, recursos enxugados, assinado com a chave da pasta
`chaves`) é montado pelo `release.sh` do SaimoPlayer, que publica todos os apps
juntos.

## Testes

```bash
gradle testDebugUnitTest
```

## Verificar

Cada mudança é conferida em emulador, não só compilada:

- **SaimoTV** — Google TV, API 34, 2 GB.
- **SaimoATV** — Android TV clássico, API 31, 1 GB: o "TV Box fraco". Sem swap,
  ele é mais severo que um aparelho real; app pesado de terceiros (YouTube,
  WebView) morre ali.

```bash
emulator -avd SaimoATV -no-snapshot-save
adb shell am start -a android.intent.action.VIEW -d "saimo://canal?n=Band"
```

## Canais e ícones

```bash
# (a lista de reserva embutida foi removida: o app não traz listas)
python3 scripts/gen_icons.py     # ícone (legado e em camadas) e banner a partir da arte do Mac, com folga para o Fire TV
```
