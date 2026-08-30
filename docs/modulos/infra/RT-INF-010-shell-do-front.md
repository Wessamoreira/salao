---
id: RT-INF-010
titulo: Shell do front
modulo: infra
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-006]
permissoes: []
eventos: []
regras: [RN-INF-011]
atualizado_em: 2026-08-30
---

# RT-INF-010 — Shell do front

## 1. Objetivo

A casca em que toda tela de negócio entra sem mexer em rota, sessão ou permissão.

## 2. RN-INF-011 — renovação de sessão é sempre uma só

**A decisão mais importante desta rotina, e ela só existe por causa de como o backend ficou.**

O refresh é rotativo com detecção de reuso (RN-IAM-007): apresentar um token já trocado revoga a
**família inteira**. Se três requisições receberem 401 ao mesmo tempo e cada uma disparar seu
próprio refresh, as três apresentam o mesmo cookie — a primeira rotaciona, e as outras duas são
lidas como reuso. **O backend derruba a sessão, corretamente, por um acidente de concorrência do
front.**

A janela de tolerância de 10 s no servidor perdoaria esse caso hoje. Depender dela seria construir
sobre uma folga que existe para rede instável, não para descuido nosso.

Por isso `renovacaoEmCurso` é uma promessa única compartilhada: a primeira chamada renova, as
demais esperam o mesmo resultado. `http.test.ts` dispara três requisições paralelas e exige
exatamente **uma** renovação.

## 3. O token vive em memória

`localStorage` é proibido (inviolável nº 6) — é XSS servido de bandeja. Em memória, um XSS usa a
sessão enquanto a aba está aberta mas não a leva embora.

O custo é que recarregar perde o token, e é exatamente para isso que existe o cookie `HttpOnly`:
no boot, `restaurarSessao()` troca o cookie por um token novo. O cookie o JavaScript não lê, então
nem um XSS o alcança.

## 4. A casca desenha o que o servidor mandou

**Não existe lista de menus neste código.** Se o backend parar de devolver o financeiro, ele some
sem ninguém tocar no front. É o que torna verdadeira a promessa do inviolável nº 6.

`Casca.test.tsx` monta com capacidades de recepção e verifica que Financeiro e Configurações
**não** aparecem — sem nenhum `if (perfil === ...)` no meio.

**A guarda de rota é conveniência, não segurança.** Ela evita piscar tela vazia para quem não pode
vê-la; quem protege é o backend, que recusa mesmo quem navegue direto pela URL.

## 5. Tokens por referência, nunca copiados

`design/tokens.css` é a fonte única, compartilhada com o canvas de design. O front a alcança por
**symlink** em `src/estilo/tokens.css`, e o Tailwind a lê com `@theme inline` — que referencia as
variáveis em vez de copiar valores.

Duas fontes de verdade para a mesma cor é como a divergência começa, e ela é silenciosa: as telas
ficam diferentes e ninguém sabe qual está certa.

## 6. Telas que ainda não existem dizem isso

`EmBreve` nomeia a rotina que a entrega, em vez de fingir conteúdo. Uma tela com dados falsos
parece pronta e engana quem revisa — inclusive o dono do salão, numa demonstração.

## 7. Testes

- [x] `http.test.ts` — 6: **uma renovação para três 401 paralelos** · repete com o token novo ·
      não entra em laço quando a renovação falha · não renova a partir das rotas de auth ·
      preserva o código estável do erro · **token nunca vai para `localStorage`**
- [x] `Casca.test.tsx` — 2: só os menus do servidor · nome do estabelecimento das capacidades

`npm test` e `npm run build` verdes; a tela de login foi aberta no navegador e conferida.

## 8. O que a verificação encontrou

**Eu violei o próprio sistema de design, e só vi na tela.** Os rótulos de campo usavam
`--tinta-tenue`, que `16-design-system` declara insuficiente para AA e restrita a texto não
essencial. Rótulo de campo é essencial. Corrigido para `--tinta-fraca` em todos os pontos.

Vale como lição: a regra estava escrita, revisada e commitada — e ainda assim foi desrespeitada na
primeira tela. Documentar não substitui olhar o resultado.

**`tsc --noEmit` passou onde `tsc -b` falhou.** O build do projeto usa `erasableSyntaxOnly`, que
proíbe propriedades declaradas no construtor. Rodar só `--noEmit` dá falsa confiança.

## 9. Pendências

- [ ] Auditoria com a skill `design:accessibility-review` — a correção de contraste foi pontual e
      por inspeção; falta a passagem completa (alvo de toque, ordem de tabulação, leitor de tela)
- [ ] Acabamento com a skill `frontend-design`: ela não estava carregada nesta sessão
- [ ] PWA (`vite-plugin-pwa`) e cache offline somente-leitura da agenda — entram com `RT-AGD-012`
- [ ] `Cmd+K` e a paleta de comandos (R-UX-02): a casca existe, o atalho ainda não
- [ ] Sentry no front, com o mesmo `release` da imagem — herdado da pendência de `RT-INF-008`
- [ ] E2E com Playwright: os 5 fluxos críticos só existem a partir da Fase 1
