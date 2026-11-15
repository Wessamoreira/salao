---
id: RT-INF-013
titulo: O app instalado — a moldura, a navegação de polegar e a janela própria
modulo: infra
fase: 7
perfil: leve
status: implementado
depende_de: [RT-INF-010]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-11-15
---

# RT-INF-013 — O app instalado

## 1. Objetivo

Fazer o sistema **instalado** se comportar como aplicativo — no celular da profissional, no
tablet do balcão e no computador da recepção — em vez de um site aberto numa janela.

## 2. Contexto

O PWA existia desde `RT-INF-010`: manifest, service worker e app shell. Instalar funcionava; o
que se instalava continuava sendo um site. Medido em 15/11/2026, com a prévia rodando em Chrome:

| Defeito | Onde | Consequência |
|---|---|---|
| Sem `viewport-fit=cover` e sem `safe-area` | iPhone, instalado | Com `black-translucent`, a barra de topo fica **debaixo** do relógio e da bateria, e o texto branco do sistema some sobre a barra branca |
| Navegação por hambúrguer | Celular e tablet em pé | Toda troca de tela custa dois toques, o primeiro no canto oposto ao polegar |
| `background_color: #f3f2ef` | Splash do Android e da instalação no desktop | Bege de uma paleta que o sistema abandonou; o splash pisca bege, o boot pisca escuro, o login pisca claro |
| `skipWaiting()` na instalação do service worker | Toda atualização | A versão nova assume no meio do uso e **apaga o cache da anterior**: a próxima tela ainda não carregada pede um arquivo que não existe mais |
| Ícone `any maskable` num arquivo só | Lançador do Android, dock do macOS | O mesmo desenho é recortado em círculo num lugar e fica com margem errada no outro |
| Janela instalada com a barra de título do navegador | Desktop | A faixa mais nobre da tela gasta com o nome da aba |
| Título da janela fixo em `Studio · Agenda` | Alt-Tab, dock, lista de janelas | Diz "Agenda" com o financeiro aberto |

## 3. A decisão de desenho — a moldura

A lateral já se chamava `--espelho` no sistema de design. A rotina leva o nome a sério: **o
espelho vira a moldura do produto inteiro**, uma superfície escura e contínua que segura uma
**folha** clara onde o trabalho acontece — o espelho na bancada do salão.

```
desktop                                  celular
┌─moldura──────────────────────────┐     ┌─moldura (status bar)─┐
│ (barra de título, se instalado)  │     │╭─folha──────────────╮│
│ ▣ Studio   ╭─folha──────────────╮│     ││ Agenda      ⌕  13:25││
│            │ SALÃO / Agenda  ⌘K ││     ││──────────────────── ││
│ Operação   │────────────────────││     ││                     ││
│ ▌Agenda    │                    ││     ││     conteúdo        ││
│  Clientes  │     conteúdo       ││     ││                     ││
│            │                    ││     │╰─────────────────────╯│
│ ● Wesley   ╰────────────────────╯│     │ ▣    ▣    ▣    ▣   ≡  │ ← abas
└──────────────────────────────────┘     └──────────────────────┘
```

Por que isto, e não mais um tratamento de superfície:

- **Ela resolve três problemas que já existiam.** A barra de título do Window Controls Overlay
  precisa de uma superfície; a área do status bar no iPhone precisa de uma superfície; a barra de
  abas precisa de uma superfície. As três são a mesma moldura — em vez de três remendos.
- **É um objeto do início ao fim.** Splash (`background_color`), boot, porta do sistema e casca
  usam a mesma superfície escura com a mesma placa de aço. Não há mais a sequência
  bege → escuro → claro.
- **O risco fica todo na estrutura.** Nenhuma cor nova, nenhuma fonte nova, nenhuma sombra nova.
  A folha não tem sombra porque não flutua — ela está **encaixada** (regra de sombra de
  `16-design-system.md` intacta). A única curva nova é o canto de 12 px da folha, que já era o
  `--raio-3` do sistema.

O custo é medido: 8 px de moldura em volta da folha no desktop. A grade da agenda perde 16 px de
largura numa tela de 1440 px (1,1%).

## 4. Atores e permissões

Nenhuma permissão nova. **A barra de abas não decide nada:** ela mostra os quatro primeiros menus
na ordem em que `/me/capabilities` os mandou, e "Mais" abre a navegação completa. Quem não tem um
menu não o vê em lugar nenhum — nem na aba, nem no "Mais", nem num atalho do manifest. Pelo mesmo
motivo **o manifest não declara `shortcuts`**: um atalho estático para `/comandas` no ícone do
celular de uma profissional seria a porta de uma tela que o servidor não deu a ela.

## 5. Fluxo principal

1. A pessoa abre o endereço; o navegador oferece instalar. Se o navegador permitir que o sistema
   peça, o menu da conta ganha **"Instalar o app"**.
2. Instalado, abre com splash escuro e a placa de aço — a mesma cor do boot.
3. **Celular:** a folha começa abaixo do status bar; a navegação está nas abas, no alcance do
   polegar; "Mais" sobe a navegação completa como folha inferior.
4. **Desktop:** a janela não tem barra do navegador. A moldura sobe até o topo, os botões da
   janela ficam sobre ela, e a faixa de cima arrasta a janela e mostra a seção e o salão.
5. Saiu versão nova: ela baixa em segundo plano e **espera**. Na barra aparece
   "Versão nova · Atualizar". Só ao clicar ela assume — com a tela recarregando inteira, e não
   pela metade.

## 6. Fluxos alternativos

**A1 — Navegador sem Window Controls Overlay** (Safari, Firefox, Chrome em aba): `env(titlebar-area-height)`
vale zero, a faixa some e sobra a moldura de 8 px.

**A2 — Tela atual fora das quatro abas** (ex.: Configurações): o "Mais" fica marcado como ativo.
Sem isso, nenhuma aba acesa diria que a pessoa não está em lugar nenhum.

**A3 — Primeira instalação do service worker:** não há versão anterior controlando a página,
então não há o que esperar — ele assume direto e o aviso de versão nova não aparece.

**A4 — Computador do balcão aberto o dia inteiro:** a verificação de versão nova roda ao voltar
para a janela e a cada hora. Sem isso, uma correção publicada às 9h só chegaria no dia seguinte.

## 7. Regras de UX aplicadas

| ID | Resumo | Garantida em |
|---|---|---|
| R-UX-02 | Teclado primeiro: o foco volta ao "Mais" ao fechar a folha | `Casca.test.tsx` |
| R-UX-22 | No toque, a navegação mora no alcance do polegar | `Casca.test.tsx` — "as abas mostram os primeiros menus do servidor" |
| R-UX-23 | Versão nova não assume no meio do trabalho | `registro.test.ts` |

## 8. Contrato de API

Nenhum endpoint novo.

## 9. Dados

Nenhuma tabela, nenhuma migration. Preferência local: nenhuma nova (a lateral recolhida continua
sendo a única).

## 10. Efeitos colaterais

| Efeito | Quando | Onde |
|---|---|---|
| `document.title` = `<seção> · <salão>` | Ao trocar de tela | Casca |
| Recarga completa da página | Ao clicar em "Atualizar" | `pwa/registro.ts` |

## 11. Casos de borda

| Situação | Comportamento |
|---|---|
| Teclado virtual aberto no celular | As abas ficam atrás do teclado (Android `resizes-visual`, iOS sobrepõe); nada sobe por cima do campo |
| Rotação do tablet | Acima de 820 px volta a lateral; abaixo, abas — a mesma régua de antes |
| Aba antiga aberta durante deploy | Continua com o bundle de antes até a pessoa atualizar; o cache da versão antiga só é apagado quando a nova assume |
| **Primeira publicação desta rotina** | Quem está com a versão anterior aberta roda um bundle que não conhece o aviso: a versão nova espera até essa janela fechar. Custo de uma vez só — a partir daí, o aviso existe |
| Clique em "Atualizar" sem rede | O botão está na barra só quando há versão nova **já baixada**; ativar não depende de rede |

## 12. Concorrência

Duas abas abertas com versão nova esperando: "Atualizar" em uma ativa a versão nova, e a outra
recarrega no mesmo `controllerchange` — as duas ficam na mesma versão, nunca uma de cada.

## 13. Observabilidade

Nada no servidor. No navegador, as falhas de registro do service worker continuam silenciosas de
propósito: sem service worker o sistema é web normal.

## 14. UX e front

- **Prévia:** `npm run previa:sistema` — a casca com a agenda real dentro, sem backend.
- **Ícones** (substituídos pelo símbolo em `RT-INF-014`): `pwa-icon.svg` (vetor, sem depender de
  fonte carregada), PNGs `any` 192/512, `maskable` 512 com a placa dentro da zona segura, e
  `apple-touch-icon` 180 opaco.
- **Capturas no manifest:** `wide` e `narrow`, para a janela de instalação rica do Chrome.

## 15. Testes obrigatórios

Verificado em 15/11/2026: 380 testes, `typecheck`, `oxlint` e `build`. A janela instalada com
Window Controls Overlay e o iPhone instalado **não** foram verificados em aparelho — só em Chrome
com área segura emulada (47/34 px). O primeiro uso real de cada um é a verificação que falta.

- [x] As abas desenham os quatro primeiros menus do servidor, e nenhum outro
- [x] O "Mais" fica ativo quando a tela atual não está nas abas
- [x] O "Mais" abre a navegação completa e devolve o foco ao fechar
- [x] Versão nova esperando não assume sozinha; "Atualizar" pede a troca e recarrega uma vez
- [x] Título da janela acompanha a seção
- [x] A suíte inteira, typecheck, lint e build passam

## Pendências

- [ ] Modo escuro da folha (F2 de `17-checklist-de-frontend.md`): a moldura já é escura; falta a
      folha, e ela exige refazer a medição de contraste de cada par de cor — não é troca de
      variável.
- [ ] Contadores nas abas e no ícone (Badging API) dependem de o servidor mandar os números (B3).
- [ ] Arrastar a folha inferior para fechar. Hoje fecha pelo X, pelo fundo e pelo Esc; uma alça
      desenhada sem o gesto atrás seria uma promessa falsa, então não há alça.
