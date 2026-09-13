# Frontend do Salão

React, TypeScript e Vite. A navegação e as permissões vêm de `/me/capabilities`; o frontend não
decide acesso por perfil.

## Desenvolvimento

```bash
npm run dev
```

O Vite encaminha `/api` para `http://localhost:8080`. O service worker é registrado apenas no
build de produção para não criar cache durante o desenvolvimento.

## PWA e offline

O build gera `sw.js` com os arquivos versionados do próprio bundle. O manifesto torna o sistema
instalável e o app shell abre sem rede depois do primeiro acesso em produção. Instalação fora de
`localhost` exige HTTPS, como todo service worker.

Dados autenticados não são gravados no Cache Storage. Se a internet cair com a sessão aberta, as
consultas já carregadas continuam na tela com aviso de horário; escritas falham imediatamente e
nunca são enfileiradas. Uma abertura offline a frio depende do futuro modo quiosque com credencial
isolada por dispositivo.

## Verificação

```bash
npm run lint
npm run typecheck
npm test
npm run build
```

As prévias das telas centrais ficam nos scripts `previa:*` do `package.json`.
