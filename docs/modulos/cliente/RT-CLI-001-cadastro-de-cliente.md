---
id: RT-CLI-001
titulo: Cadastro de cliente (nome + telefone E.164, resto opcional)
modulo: cliente
fase: 1
perfil: completo
status: implementado
depende_de: [RT-INF-002, RT-IAM-006]
permissoes: [cliente:write, cliente:read]
eventos: []
regras: [RN-CLI-001, RN-CLI-002, RN-CLI-003, RN-CLI-004, RN-CLI-005]
atualizado_em: 2026-08-30
---

# RT-CLI-001 — Cadastro de cliente

## 1. Objetivo

Registrar uma pessoa com o mínimo que o salão realmente sabe dela no momento em que ela aparece —
que quase sempre é **só o nome e um telefone**.

Abre a Fase 1 porque agendar exige alguém para quem agendar.

## 2. Um caminho só, não dois

O "cadastro rápido" (R-UX-04, dois campos, sem sair da tela de agendamento) e o cadastro completo
são **o mesmo caso de uso**. A diferença entre eles é quantos campos vieram preenchidos, e nada
mais.

Dois caminhos separados divergiriam na primeira regra nova — e ela acabaria valendo só no
completo, justamente o menos usado. Não existe endpoint de cadastro rápido: é `POST
/api/v1/clientes` com menos campos.

## 3. Telefone é a decisão central desta rotina

É a maior fonte de erro de cadastro num salão, e o motivo é banal: quem digita está com o telefone
na orelha e a cliente na frente. O mesmo número chega como `(11) 98765-4321`, `11987654321`,
`+55 11 98765-4321` e `98765-4321`.

Se cada forma virar uma linha diferente, a busca por telefone (R-UX-03) deixa de funcionar
**exatamente para quem mais precisa dela**: quem atende o telefone tocando. Daí o tipo `Telefone`
(RN-CLI-001), com sete formas de entrada cobertas por teste.

Três decisões dentro dele merecem registro:

**Guarda só dígitos, em E.164.** Formatar na gravação faria a busca por sufixo depender de como
cada pessoa digitou a máscara. Formatar é trabalho do front.

**Completa o DDD do estabelecimento** (RN-CLI-002). Quem atende anota `98765-4321`: o DDD é óbvio
para quem está ali. Exigi-lo é fricção que faz a recepção inventar dado. Um DDD informado —
cliente de outro estado — é respeitado, nunca sobrescrito.

**Não inventa o nono dígito** (RN-CLI-003). Um fixo de oito dígitos é válido. Adivinhar
transformaria um número certo em errado sem ninguém perceber, e o sintoma apareceria só quando
alguém tentasse ligar para a cliente. Recusar é melhor que chutar.

O campo `telefone` do controller **não tem `@Pattern`** de propósito: validar formato na borda
recusaria `(11) 98765-4321` com um 400 genérico, antes de o domínio ter chance de normalizar.
`ClientesWebIT.telefone_com_mascara_e_aceito` reprova o build se alguém adicionar um.

## 4. O que é obrigatório: só o nome

Nem telefone (RN-CLI-004). Cliente que passa e pergunta o preço, criança que vem com a mãe, a
senhora que não tem celular: existem e precisam de ficha. Exigir telefone faria a recepção digitar
`00000-0000`, que é **pior que o vazio** — parece dado e não é.

## 5. Telefone repetido é permitido, e isso é domínio

Não há `unique` em telefone (RN-CLI-005). Mãe e filha compartilham o número o tempo todo num
salão; marido e esposa também.

Uma restrição aqui recusaria cadastro legítimo e ensinaria a recepção a inventar um número falso
para contornar — que é o pior desfecho possível. Duplicado é tratado por **detecção** no momento
do cadastro (RT-CLI-003), não por proibição.

## 6. O que a fronteira de módulo revelou

O `dddPadrao` é configuração do estabelecimento, e portanto do módulo `iam`. O `cliente` o lê por
`EstabelecimentoApi` — nunca por join.

Ao fazer isso, o build reprovou: o Spring Modulith expõe **apenas o pacote-raiz** do módulo, e o
subpacote `api` não passava de convenção de nome. A documentação afirmava desde o começo que "só
`api` é visível", e isso simplesmente não era verdade no build — ninguém tinha percebido porque
nenhum módulo dependia de outro ainda.

Corrigido com `@NamedInterface("api")` em todos os onze módulos. Verificado do jeito certo:
importando `iam.internal.domain.Emails` de dentro do `cliente` de propósito e confirmando que o
build cai.

É a mesma lição de antes, agora do lado do backend: **documentar não substitui verificar**.

## 7. Autoria: uma pergunta, uma resposta

`criado_por` precisava de "quem é o usuário atual", que já existia privado dentro de
`AuditoriaJdbc`. Duas implementações da mesma pergunta divergiriam na primeira mudança — e a
divergência apareceria numa trilha de auditoria dizendo uma coisa e numa coluna de autoria dizendo
outra, meses depois, sem quem conciliar.

Extraído para `shared.seguranca.UsuarioAtual`.

## 8. LGPD: o que **não** vai para a auditoria

Só o nome. Auditoria é retida por prazo longo e consultada por muita gente: copiar telefone e
e-mail para dentro dela criaria uma segunda base de dados pessoais fora do alcance da anonimização
(RT-CLI-005), que só sabe limpar a tabela `cliente`.

Pelo mesmo motivo a coluna `anonimizado_em` já existe: anonimizar apaga o dado pessoal e **mantém
a linha**, porque o histórico financeiro precisa continuar apontando para alguém.

## 9. Testes

- [x] `TelefoneTest` — 19: sete formas do mesmo número · completa o DDD · fixo de oito dígitos
      sem inventar o nono · recusa sem DDD padrão · respeita DDD de outro estado · últimos quatro
      dígitos · formatação de exibição
- [x] `CadastroDeClienteIT` — 9: cadastro rápido de dois campos · DDD completado pelo salão ·
      recusa em vez de chutar sem DDD configurado · cliente sem telefone · **mãe e filha com o
      mesmo número** · nome vazio aponta o campo · espaço a mais não cria segunda pessoa · sem
      `cliente:write` é recusado no caso de uso · isolamento entre tenants pela RLS
- [x] `ClientesWebIT` — 4: máscara aceita na borda e normalizada · 422 com o campo apontado ·
      nome ausente recusado · sem token não cadastra
- [x] `NovoEstabelecimentoTest` — 2 novos: DDD `011` recusado · sem DDD é aceito

## 10. Pendências

- [x] ~~Tela de configuração do DDD padrão~~ — entregue em RT-CFG-001, que também revelou que a
      normalização do DDD estava no command de provisionamento e não no domínio
- [ ] **Ficha técnica de química** (dado de saúde, LGPD): exige `cliente:ficha:read` e cifragem de
      campo via `CofreDeCampo`. Rotina própria, ainda não planejada — hoje `observacao` é texto
      livre e **não deve** receber informação clínica
- [ ] **Aniversário**: o salão usa para campanha, e a coluna ainda não existe. Entra junto com o
      módulo `notificacao`
- [ ] **Consentimento LGPD** (base legal, data, canal): RT-CLI-004
- [ ] Endereço: só quando houver atendimento domiciliar no escopo
- [ ] Listagem paginada de clientes — depende de RT-CLI-002, que traz a busca
