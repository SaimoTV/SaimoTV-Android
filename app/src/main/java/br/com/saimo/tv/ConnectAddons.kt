package br.com.saimo.tv

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Endpoints HTTP na porta 8999 do Saimo Connect para configurar Addons Stremio
 * e provedores Debrid pelo navegador do celular/PC na rede local:
 * - `GET /addons` -> página web responsiva em português
 * - `GET /api/addons` -> estado atual em JSON (com chaves Debrid mascaradas)
 * - `POST /addons` -> ações `add`, `toggle`, `move`, `remove`, `test`, `debrid`, `debrid_sync`
 */
object ConnectAddons {

    fun responderGetHtml(context: Context, output: OutputStream) {
        val ip = Connect.obterIpLocal().ifBlank { "tv.local" }
        val html = gerarHtml(ip)
        escreverResposta(output, 200, "OK", "text/html; charset=utf-8", html)
    }

    fun responderGetApi(output: OutputStream) {
        val payload = estadoJson().toString()
        escreverResposta(output, 200, "OK", "application/json; charset=utf-8", payload)
    }

    fun responderPost(context: Context, corpoBruto: String, output: OutputStream) {
        val req = runCatching { JSONObject(corpoBruto) }.getOrNull()
        if (req == null) {
            val erro = JSONObject().put("ok", false).put("erro", "Corpo JSON inválido.").toString()
            escreverResposta(output, 400, "Bad Request", "application/json; charset=utf-8", erro)
            return
        }

        val (status, resp) = executarAcao(req)
        if (resp.optBoolean("ok", false)) {
            runCatching { Connect.avisarMudancaLocal(context, atrasoMs = 300) }
        }
        val codigo = if (status == 200) "OK" else "Bad Request"
        escreverResposta(output, status, codigo, "application/json; charset=utf-8", resp.toString())
    }

    /** Executa uma ação de gerenciamento e devolve `(httpStatus, jsonResposta)`. */
    fun executarAcao(req: JSONObject): Pair<Int, JSONObject> {
        return when (val acao = req.optString("acao", "").trim().lowercase()) {
            "add" -> {
                val url = req.optString("url", "").trim()
                try {
                    val instalado = runBlocking { AddonsStore.instalar(url) }
                    200 to JSONObject()
                        .put("ok", true)
                        .put("mensagem", "Addon ${instalado.nome} instalado com sucesso.")
                        .put("estado", estadoJson())
                } catch (e: StremioProtocol.AddonException) {
                    400 to JSONObject().put("ok", false).put("erro", e.message ?: "Addon inválido.")
                } catch (e: Exception) {
                    400 to JSONObject().put("ok", false).put("erro", e.message ?: "Erro ao instalar addon.")
                }
            }
            "toggle" -> {
                val chave = req.optString("chave", "")
                val hab = if (req.has("habilitado")) req.optBoolean("habilitado") else null
                AddonsStore.alternar(chave, hab)
                200 to JSONObject().put("ok", true).put("estado", estadoJson())
            }
            "move" -> {
                val chave = req.optString("chave", "")
                val delta = req.optInt("delta", 0)
                AddonsStore.mover(chave, delta)
                200 to JSONObject().put("ok", true).put("estado", estadoJson())
            }
            "remove" -> {
                val chave = req.optString("chave", "")
                AddonsStore.remover(chave)
                200 to JSONObject().put("ok", true).put("estado", estadoJson())
            }
            "test" -> {
                val chave = req.optString("chave", "")
                try {
                    val res = runBlocking { AddonsStore.testar(chave) }
                    200 to JSONObject()
                        .put("ok", true)
                        .put("mensagem", "Manifesto OK: ${res.nome} v${res.versao}")
                        .put("estado", estadoJson())
                } catch (e: StremioProtocol.AddonException) {
                    400 to JSONObject().put("ok", false).put("erro", e.message ?: "Falha ao testar addon.")
                }
            }
            "debrid" -> {
                val cod = req.optString("provedor", "")
                val prov = DebridStore.Provedor.porCodigo(cod)
                    ?: return 400 to JSONObject().put("ok", false).put("erro", "Provedor Debrid desconhecido.")
                val atual = DebridStore.configDe(prov)
                val manter = req.optBoolean("manterChave", false)
                val novaChave = if (manter && req.optString("apiKey", "").isBlank()) {
                    atual.apiKey
                } else {
                    req.optString("apiKey", "").trim()
                }
                val habilitado = if (req.has("habilitado")) req.optBoolean("habilitado", true) else novaChave.isNotBlank()
                DebridStore.definirProvedor(prov, novaChave, habilitado)
                200 to JSONObject()
                    .put("ok", true)
                    .put("mensagem", "${prov.nome} atualizado.")
                    .put("estado", estadoJson())
            }
            "debrid_sync" -> {
                DebridStore.definirSincronizarConnect(req.optBoolean("ativo", false))
                200 to JSONObject().put("ok", true).put("estado", estadoJson())
            }
            else -> 400 to JSONObject().put("ok", false).put("erro", "Ação '$acao' não suportada.")
        }
    }

    fun estadoJson(): JSONObject {
        val addonsArr = JSONArray().apply {
            AddonsStore.listar().forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("nome", a.nome)
                    put("versao", a.versao)
                    put("descricao", a.manifesto.description)
                    put("transportUrl", a.transportUrl)
                    put("habilitado", a.habilitado)
                    put("recursos", JSONArray(a.manifesto.resources.map { it.nome }))
                    put("catalogos", a.manifesto.catalogs.size)
                })
            }
        }
        val provedoresObj = JSONObject().apply {
            for (p in DebridStore.Provedor.entries) {
                val cfg = DebridStore.configDe(p)
                val mascara = if (cfg.apiKey.length <= 4) {
                    if (cfg.apiKey.isBlank()) "" else "****"
                } else {
                    "****${cfg.apiKey.takeLast(4)}"
                }
                put(p.codigo, JSONObject().apply {
                    put("nome", p.nome)
                    put("habilitado", cfg.habilitado)
                    put("configurado", cfg.apiKey.isNotBlank())
                    put("mascara", mascara)
                })
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("addons", addonsArr)
            .put("debrid", JSONObject()
                .put("sincronizarConnect", DebridStore.sincronizarConnectAtivo())
                .put("provedores", provedoresObj))
    }

    private fun escreverResposta(
        output: OutputStream,
        codigo: Int,
        status: String,
        contentType: String,
        corpo: String,
    ) {
        val bytes = corpo.toByteArray(StandardCharsets.UTF_8)
        val cabecalho = "HTTP/1.1 $codigo $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(cabecalho.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun gerarHtml(ip: String): String = """<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>Saimo Connect • Addons e Debrid</title>
<style>
  :root { color-scheme: dark; --bg: #0b0e14; --card: #131922; --line: #232c3b; --accent: #4fe3d0; --txt: #ffffff; --sub: #a8b4c0; --err: #ff6b6b; }
  * { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body { margin: 0; background: var(--bg); color: var(--txt); padding: 20px; }
  .wrap { max-width: 720px; margin: 0 auto; }
  h1 { margin: 0 0 6px; font-size: 24px; }
  p.sub { margin: 0 0 18px; color: var(--sub); font-size: 14px; }
  .card { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 16px; margin-bottom: 16px; }
  .row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
  input[type=text], input[type=password] { flex: 1; min-width: 220px; background: #0b0e14; color: var(--txt); border: 1px solid var(--line); border-radius: 10px; padding: 11px 12px; font-size: 14px; }
  button { background: var(--accent); color: #081014; border: 0; border-radius: 10px; padding: 10px 14px; font-weight: 700; font-size: 13px; cursor: pointer; }
  button.sec { background: #1c2432; color: var(--txt); border: 1px solid var(--line); }
  button.del { background: #38181f; color: #ff8e96; border: 1px solid #592630; }
  .msg { margin-top: 10px; font-size: 13px; min-height: 18px; }
  .msg.ok { color: var(--accent); }
  .msg.err { color: var(--err); }
  .item { display: flex; justify-content: space-between; gap: 12px; align-items: center; border-top: 1px solid var(--line); padding: 12px 0; flex-wrap: wrap; }
  .item:first-child { border-top: 0; padding-top: 4px; }
  .meta { font-size: 12px; color: var(--sub); margin-top: 4px; }
  .badge { display: inline-block; background: #1c2636; color: var(--accent); padding: 2px 8px; border-radius: 999px; font-size: 11px; margin-right: 6px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>Saimo TV • Addons e Debrid</h1>
  <p class="sub">Conectado à TV em <b>http://$ip:${Connect.PORTA}</b>. Suporta addons HTTP do Stremio (manifest.json / stremio://).</p>

  <div class="card">
    <h3 style="margin:0 0 10px">Instalar Addon Stremio</h3>
    <div class="row">
      <input id="addonUrl" type="text" placeholder="stremio://... ou https://.../manifest.json" />
      <button onclick="addAddon()">Instalar</button>
    </div>
    <div id="msgAddon" class="msg"></div>
  </div>

  <div class="card">
    <h3 style="margin:0 0 10px">Addons Instalados</h3>
    <div id="listaAddons"><p class="meta">Carregando…</p></div>
  </div>

  <div class="card">
    <h3 style="margin:0 0 6px">Provedores Debrid (Torrents)</h3>
    <p class="sub" style="margin-bottom:12px">As chaves são salvas localmente na TV. Resolução direta via API REST oficial sem P2P local.</p>
    <div id="listaDebrid"></div>
    <div class="item" style="margin-top:8px">
      <div>
        <b>Sincronizar chaves via Saimo Connect</b>
        <div class="meta">Opcional (desligado por padrão). Envia chaves Debrid no pacote de pareamento local.</div>
      </div>
      <button id="btnSync" class="sec" onclick="toggleSync()">Carregando</button>
    </div>
    <div id="msgDebrid" class="msg"></div>
  </div>
</div>
<script>
let estadoAtual = null;
async function api(body) {
  const r = await fetch('/addons', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  return r.json();
}
function setMsg(id, txt, ok) {
  const el = document.getElementById(id);
  el.textContent = txt || '';
  el.className = 'msg ' + (txt ? (ok ? 'ok' : 'err') : '');
}
async function carregar() {
  const r = await fetch('/api/addons');
  estadoAtual = await r.json();
  render();
}
function esc(s) {
  return String(s || '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
function render() {
  if (!estadoAtual) return;
  const la = document.getElementById('listaAddons');
  const addons = estadoAtual.addons || [];
  if (!addons.length) {
    la.innerHTML = '<p class="meta">Nenhum addon instalado ainda. Cole a URL acima para começar.</p>';
  } else {
    la.innerHTML = addons.map((a, i) => {
      const rec = (a.recursos || []).map(r => '<span class="badge">' + esc(r) + '</span>').join('');
      return '<div class="item"><div><b>' + esc(a.nome) + '</b> <span class="meta">v' + esc(a.versao) +
        (a.habilitado ? ' • Ativo' : ' • Desativado') + '</span><div class="meta">' + rec + '</div></div>' +
        '<div class="row">' +
        '<button class="sec" onclick="acaoAddon(\'toggle\',\'' + encodeURIComponent(a.transportUrl) + '\',' + (!a.habilitado) + ')">' + (a.habilitado ? 'Desativar' : 'Ativar') + '</button>' +
        (i > 0 ? '<button class="sec" onclick="moverAddon(\'' + encodeURIComponent(a.transportUrl) + '\',-1)">↑</button>' : '') +
        (i < addons.length - 1 ? '<button class="sec" onclick="moverAddon(\'' + encodeURIComponent(a.transportUrl) + '\',1)">↓</button>' : '') +
        '<button class="sec" onclick="testarAddon(\'' + encodeURIComponent(a.transportUrl) + '\')">Testar</button>' +
        '<button class="del" onclick="removerAddon(\'' + encodeURIComponent(a.transportUrl) + '\')">Remover</button>' +
        '</div></div>';
    }).join('');
  }
  const provs = (estadoAtual.debrid && estadoAtual.debrid.provedores) || {};
  const ld = document.getElementById('listaDebrid');
  ld.innerHTML = Object.keys(provs).map(cod => {
    const p = provs[cod];
    const st = p.habilitado ? ('Ativo (' + p.mascara + ')') : (p.configurado ? ('Desativado (' + p.mascara + ')') : 'Não configurado');
    return '<div class="item"><div style="flex:1;min-width:220px"><b>' + esc(p.nome) + '</b> <span class="meta">' + esc(st) + '</span>' +
      '<div class="row" style="margin-top:8px"><input id="key_' + cod + '" type="password" placeholder="Nova API Key (deixe vazio para manter)" />' +
      '<button onclick="salvarDebrid(\'' + cod + '\')">Salvar</button>' +
      (p.configurado ? '<button class="sec" onclick="alternarDebrid(\'' + cod + '\',' + (!p.habilitado) + ')">' + (p.habilitado ? 'Desativar' : 'Ativar') + '</button>' : '') +
      (p.configurado ? '<button class="del" onclick="limparDebrid(\'' + cod + '\')">Limpar</button>' : '') +
      '</div></div></div>';
  }).join('');
  const sync = estadoAtual.debrid && estadoAtual.debrid.sincronizarConnect;
  document.getElementById('btnSync').textContent = sync ? 'Ligado' : 'Desligado';
}
async function addAddon() {
  const input = document.getElementById('addonUrl');
  setMsg('msgAddon', 'Validando manifesto…', true);
  const res = await api({ acao: 'add', url: input.value });
  if (res.ok) {
    input.value = '';
    estadoAtual = res.estado;
    render();
    setMsg('msgAddon', res.mensagem, true);
  } else {
    setMsg('msgAddon', res.erro || 'Erro ao adicionar.', false);
  }
}
async function acaoAddon(acao, encUrl, hab) {
  const res = await api({ acao, chave: decodeURIComponent(encUrl), habilitado: hab });
  if (res.ok) { estadoAtual = res.estado; render(); }
}
async function moverAddon(encUrl, delta) {
  const res = await api({ acao: 'move', chave: decodeURIComponent(encUrl), delta });
  if (res.ok) { estadoAtual = res.estado; render(); }
}
async function testarAddon(encUrl) {
  setMsg('msgAddon', 'Testando manifesto…', true);
  const res = await api({ acao: 'test', chave: decodeURIComponent(encUrl) });
  setMsg('msgAddon', res.ok ? res.mensagem : res.erro, !!res.ok);
}
async function removerAddon(encUrl) {
  const res = await api({ acao: 'remove', chave: decodeURIComponent(encUrl) });
  if (res.ok) { estadoAtual = res.estado; render(); }
}
async function salvarDebrid(cod) {
  const key = document.getElementById('key_' + cod).value;
  const res = await api({ acao: 'debrid', provedor: cod, apiKey: key, habilitado: true, manterChave: !key });
  if (res.ok) { estadoAtual = res.estado; render(); setMsg('msgDebrid', res.mensagem, true); }
  else setMsg('msgDebrid', res.erro, false);
}
async function alternarDebrid(cod, hab) {
  const res = await api({ acao: 'debrid', provedor: cod, habilitado: hab, manterChave: true });
  if (res.ok) { estadoAtual = res.estado; render(); setMsg('msgDebrid', res.mensagem, true); }
}
async function limparDebrid(cod) {
  const res = await api({ acao: 'debrid', provedor: cod, apiKey: '', habilitado: false });
  if (res.ok) { estadoAtual = res.estado; render(); setMsg('msgDebrid', res.mensagem, true); }
}
async function toggleSync() {
  const atual = !!(estadoAtual && estadoAtual.debrid && estadoAtual.debrid.sincronizarConnect);
  const res = await api({ acao: 'debrid_sync', ativo: !atual });
  if (res.ok) { estadoAtual = res.estado; render(); }
}
carregar();
</script>
</body>
</html>"""
}
