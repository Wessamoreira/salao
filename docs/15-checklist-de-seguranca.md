# Checklist de segurança

Auditado em 29/08/2026 contra o código, não de memória. Cada linha traz **onde verificar**, para
que a próxima auditoria não precise confiar nesta.

## Resumo

| # | Item | Situação |
|---|---|---|
| 1 | RLS no banco | ✅ Feito, com teste que quebra o build |
| 2 | Rotas de API protegidas | ✅ Feito, fecha por padrão |
| 3 | Senha protegida no banco | ✅ Argon2id |
| 4 | Rate limit em tentativas de login | ⚠️ **Parcial** — por usuário sim, por IP não |
| 5 | Dados do usuário cifrados | ⚠️ **Parcial, e é o correto por ora** |
| 6 | Chave exposta no deploy | 🔴 **Havia. Corrigido nesta auditoria** |
| 7 | CORS | ❌ Não configurado |
| 8 | Cabeçalhos de segurança (HSTS, CSP) | ❌ Não configurados |
| 9 | `statement_timeout` / `lock_timeout` | ❌ Não configurados |
| 10 | Actuator autenticado | ⚠️ Só em porta separada |

---

## 1. RLS no banco ✅

Toda tabela de negócio tem `ENABLE` **e** `FORCE ROW LEVEL SECURITY`, com policy por
`estabelecimento_id`. A aplicação conecta com role que **não é dona** das tabelas — sem isso o
Postgres ignoraria a RLS em silêncio.

**Onde verificar:** `SchemaIT.toda_tabela_de_negocio_tem_estabelecimento_id_rls_e_force` varre o
schema real e **quebra o build** quando uma migration nova esquece. É o que faz a garantia
sobreviver ao tempo, e não só existir hoje.

Também testado: query sem tenant devolve **zero linhas**, não todas — a falha é fechada.

## 2. Rotas de API protegidas ✅

`anyRequest().access(...)`: **endpoint novo nasce protegido**, e abrir exige ato deliberado. O
contrário — abrir por padrão e lembrar de fechar — falha na primeira distração.

Abertos de propósito, cada um com motivo escrito no código: `login`, `refresh` (usado quando o
access token já expirou), `logout` (sair não pode depender de token expirado) e `mfa/verificar`
(quem chega tem só o desafio, que sozinho não abre nada).

**Onde verificar:** `AutorizacaoWebIT.sem_token_nada_passa` e
`AutenticacaoWebIT.rota_desconhecida_exige_autenticacao`.

## 3. Senha protegida no banco ✅

**Argon2id** — e a distinção importa: senha é **hasheada**, não criptografada. Criptografia é
reversível por quem tem a chave; hash não é reversível por ninguém. Se alguém obtiver o banco, não
há chave que devolva as senhas.

Argon2id em vez de BCrypt porque é caro em **memória**, que é o recurso difícil de paralelizar
barato — BCrypt resiste a GPU, mas não a hardware com muita RAM.

**Onde verificar:** `SegurancaConfig.passwordEncoder`.

## 4. Rate limit em tentativas de login ⚠️

**O que existe:** bloqueio progressivo **por usuário**, contado no banco. Quatro falhas não
bloqueiam (errar a senha é normal); a partir daí dobra até o teto de 15 minutos. O teto existe
para o bloqueio não virar negação de serviço contra alguém cujo e-mail foi atacado.

**O que falta:** limite **por IP**. Hoje, um atacante que tente uma senha comum contra mil e-mails
diferentes não dispara bloqueio nenhum — cada conta acumula uma falha só. É o ataque de
*password spraying*, e ele é exatamente o que o limite por usuário não pega.

**Onde verificar:** `PoliticaDeBloqueioTest` (o que existe); ausência de `Bucket4j` no `pom.xml`
(o que falta).

→ **Entra como `RT-INF-011`.**

## 5. Dados do usuário cifrados ⚠️

**Cifrado:** o segredo TOTP (`CofreDeCampo`, AES-256-GCM, chave fora do banco). Ele é equivalente
ao segundo fator inteiro — quem o obtém gera códigos válidos para sempre.

**Não cifrado, e corretamente:** nome, e-mail e perfil. Cifrar tudo é um erro comum: impede busca
e ordenação por índice, e a chave fica no mesmo processo que lê os dados, então não protege contra
quem já executa código na aplicação. Cifragem de campo protege contra quem obtém **os dados** —
dump, backup, `SELECT` indevido —, não contra quem obtém **o processo**.

**Ainda não existe, e vai precisar:** a ficha técnica do cliente (histórico de química indica
alergia — dado de saúde pela LGPD). O mecanismo está pronto e testado; falta o módulo `cliente`
(Fase 1, `RT-CLI-001`).

**Onde verificar:** `SegundoFatorIT.segredo_nao_fica_em_claro` lê a coluna direto e confirma.

## 6. Chave exposta no deploy 🔴 → ✅

**Havia um problema real, encontrado nesta auditoria e corrigido.**

`application.yml` — versionado no GitHub — trazia as senhas do banco com **valor padrão**
(`salao_app_dev`, `salao_owner_dev`, `salao_manutencao_dev`). Subir em produção esquecendo as
variáveis de ambiente faria a aplicação funcionar normalmente, com senhas publicadas no
repositório. Sem erro, sem aviso, até alguém reparar.

`JWT_SEGREDO` e `CRIPTO_CHAVE` já estavam certos: sem padrão, a aplicação recusa subir.

**Corrigido:** nenhuma senha tem padrão em `application.yml`. Os valores de desenvolvimento
passaram para `application-dev.yml`, onde é evidente que são de desenvolvimento e onde produção
nunca os herda.

**A regra:** falhar na subida é barulhento e imediato; funcionar com credencial fraca é silencioso
e dura até alguém descobrir.

### Duas exposições que permanecem

**A senha da role vai dentro do SQL da migration** (`alter role salao_app password '...'`). Se o
Postgres estiver com `log_statement = 'ddl'` ou `'all'`, ela aparece no log do banco. Não é
exposição no repositório — o valor vem de variável —, mas é exposição no log do servidor.
→ **Entra como `RT-INF-012`.**

**O `.env` está protegido** (`.gitignore` cobre `.env`, `.env.*`, `*.pem`, `*.key` desde o
primeiro commit), e a imagem Docker não embute segredo nenhum: tudo entra por variável em tempo
de execução.

## 7. CORS ❌

Não configurado. Enquanto não há front, nenhum navegador chama a API de outra origem — mas isso
muda no `RT-INF-010`, e o padrão do Spring sem configuração explícita não é o que se quer.
`05-seguranca` promete origem explícita, nunca `*`.
→ **Entra como `RT-INF-011`.**

## 8. Cabeçalhos de segurança ❌

HSTS, CSP, `X-Content-Type-Options` e `Referrer-Policy` estão prometidos em `05-seguranca` e não
foram configurados. Só passam a valer com o front e com TLS na frente, mas a configuração é barata
e deve entrar junto do CORS.
→ **Entra como `RT-INF-011`.**

## 9. `statement_timeout` e `lock_timeout` ❌

Prometidos em `08-dados` e em `RT-INF-005`. Sem `statement_timeout`, uma query ruim segura conexão
até o fim do mundo. Sem `lock_timeout`, a segunda requisição idempotente concorrente espera
indefinidamente pela primeira — o custo assumido em `RT-INF-005` está hoje **sem teto**.
→ **Entra como `RT-INF-012`.**

## 10. Actuator ⚠️

Está em porta separada (9090), fora da rota pública — mas **sem autenticação**: qualquer um dentro
da rede alcança `/actuator/health` e `/actuator/prometheus`. Health e métrica precisam ser legíveis
por sonda de contêiner e por scraper, que não carregam token, então a solução não é simplesmente
exigir Bearer.
→ **Entra como `RT-INF-012`**: restrição por rede na VM, e autenticação básica no scraper.

---

## O que já está bem, e vale não perder

- Refresh rotativo com **detecção de reuso** que revoga a família inteira (RN-IAM-007)
- Cookie `HttpOnly` + `Secure` + `SameSite=Strict` + `Path` restrito; refresh **nunca** no corpo
- MFA **imposto pelo backend**, não só sinalizado ao front (RN-IAM-014)
- Um código de erro só para senha errada, e-mail inexistente e usuário inativo — sem enumeração
- Auditoria **imutável por permissão**, com campos sensíveis omitidos (RN-IAM-017)
- Bot com conjunto de permissões **vazio**: age em nome de alguém, herdando (evita *confused deputy*)
- Provisionamento e login cross-tenant isolados numa role de plataforma separada (ADR-0010)
