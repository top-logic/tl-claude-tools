# Change the chat after the integration

**[React]** The app's view `views/<app>/albert.view.xml` is the chat. Change it directly. If the app uses the page that the engine ships, copy that view into the app first (same procedure as for the classic tab below), and point the `<view-ref>` to the copy.

**[Classic]** The tab shows `views/tl-chat-classic/chat.view.xml` of `tl-chat-classic`. To change it:

1. Copy the file to `views/<app>/chat.view.xml`. Change the copy.
2. Point the tab to the copy: a file with the same path `layouts/tl-chat/chat/agent.xml` with `<component class="com.top_logic.ai.chat.classic.ReactViewComponent" name="AgentContent" resPrefix="chat.layout.agent" view="<app>/chat.view.xml"/>`.
3. Compare the copy with the original after each TopLogic update. A copy does not get the changes of an update.

The view only hides controls. What an account may do is set by the policy of `AiToolAccessService`. Without an entry in `<model-access>`, an account cannot select a model other than the model of its agent (`default="assigned"`).

| Goal | Place in the chat view |
| --- | --- |
| Hide the selection of model, agent, MCP, attachments, thinking | `<controls model-picker="false" agent-picker="false" mcp="false" attachments="false" thinking="false"/>` in `<chat-conversation>` |
| Hide copy, rate, regenerate below the answers | `<actions copy="false" rate="false" regenerate="false"/>` in `<chat-conversation>` |
| Own text in the input field, start questions | `<placeholder>`, `<prompts>` in `<chat-conversation>` |
| Group the chats by date | `<group-by>` in the `<data-list>`. Example: tl-ai-demo `chat-grouped.view.xml` |
| Rename a chat | A command in `<row-commands>` with a dialog. Example: tl-ai-demo `rename-session.view.xml` (writes with `aiSessionRename`) |
| Prevent the deletion of a chat | Remove the delete command from `<row-commands>` |
| Show a command only when it is allowed | `<executability><visible-if>` in the command |
| Show the tables of Albert | Channel `paneObject`, `present="paneObject"` and `<selection channel="paneObject"/>` on `<chat-conversation>`, `input="paneObject"` on `<chat-rail>`. Keep all parts together. |
| Tell Albert what the page shows | `<chat-channels>` in the app shell and `<page-selection>` in the page. Example: tl-ai-demo `app.view.xml` |

The examples of tl-ai-demo are in the engine sources (module `tl-ai-demo`, `WEB-INF/views/`). Use the version of the engine that the app builds against.
