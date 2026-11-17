# Protocolo Modo Deus (Projeto)

- **Orquestrador (modelo principal / `pro`)**: define objetivo mensuravel em `memoria/objetivo.md`, planeja, delega e audita. Nao le diretorios extensos nem executa edicoes em lote direto na thread principal.
- **Executores (`flash_lite` / `flash`)**: executam leituras, buscas, edicoes escopadas e testes via `invoke_subagent` ou `run_workflow`.
- **Verificador (contexto limpo)**: valida diff, testes e criterios de aceite sem editar codigo. Maximo 3 ciclos; na 2a falha igual, escalar causa-raiz.
- **Contexto 40% / 60%**: salvar estado em `memoria/estado.md` e solicitar `/compact` ao atingir 40% (rotina) ou 60% (tarefas grandes).
- **Autoaprimoramento**: registrar licoes em `memoria/aprendizados.md` e propor diffs em skills/regras apenas com aprovacao do usuario.
