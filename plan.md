Agentic coding tools are getting fairly competent at writing code, but tend to fail at managing large scale projects requiring big context windows. However, this isn't a problem unique to LLMs, humans struggle at keeping large amounts of code in their heads as well. Shared mutable state is one of the major source of bugs in software projects, where we end up losing track of different relationships in code, and mutating state in ways that lead to unintended consequences.

The way we manage complexity is by breaking down large problems into sets of smaller ones that we compose together. Effective architecture requires creating isolated components whose functionality we can fit in our heads, and then creating interfaces between them that abstract over their internal details and focus on their functional aspects. And that naturally leads to the need for hierarchical organization.

Hierarchies allow creating independent units that can compose together to build larger structures. It's a type of structure that makes it possible to write large scale projects by abstracting over complexity. A large system needs to be resilient and adaptable. But if every single part is directly connected to every other part, the effects of any change become impossible to predict because we end up being overwhelmed by the flow of information.

Organizing a large system into nested subsystems creates cells that talk to each other to do their job. They don’t need to know the internal processes of other cells, and act as stable subassemblies. Each level can self-organize and maintain resilience within its own domain because it’s not bogged down by what’s happening elsewhere.

And that’s how hierarchical structures allow us to manage complexity within the system. Each subsystem can evolve independently, and a problem in one area can be contained without crashing the whole system. A good way to think of hierarchies is to treat them as connective tissue between the components of the systems. It's a central control mechanism that provides the structure and the scaffolding to coordinate the functionality of individual components within the system.

Software industry has developed many tools and approaches to help us write code in this style. At high level we have patterns like microservices where we create physical boundaries between components by turning them into independent programs. Meanwhile, at individual program level we have approaches such as message passing, pure functions, and immutable data.

Functional style in particular focuses on structuring code in a way where functions are the smallest building blocks that can be reasoned about independently. The core philosophy is to structure systems out of discrete, single-purpose components. It's about applying the principles of isolation, composition, and explicit contracts to the internal logic of applications themselves.

The key insight of the functional approach is to focus on the data flow, and to make data a first class citizen that we model the application around. Ultimately, all that code is doing is transform data. A program starts with one piece of data as the input and produces another as its output. Modern FP style embraces the fact that programs can be viewed as data transformation pipelines where input data is passed through a series of pure functions to transform it into desired output. Such functions can be reasoned about in isolation without having to consider the rest of the program. Plain data doesn't have any hidden behaviors or state that you have to worry about. Immutable data is both transparent and inert. Data can also be passed across program boundaries since it's directly serializable. Data oriented style makes it possible to make stable contracts. Once the API consisting of all the supported transformations has been defined and tested, then the API is complete. It becomes a contract that can be relied on.

When writing applications using data oriented style we can think of the overall application structure as kind of railway network. The input data is the payload which is the package that needs to be delivered to the correct destination. The logic at the edges of the application tends to deal with the contents of the payload. For example, an HTTP handler might need to parse the request payload, look at its content, and then send it off to the auth handler that will check whether the payload has the right permissions, and so on. Eventually, the payload will make it to its destination where it will be serialized to be sent to another service, stored in a database, or displayed to the user.

A good way to express this flow is by representing it using a state machine. Each station or node in our rail network can inspect or modify the payload, and then route it to the next step. Here, we can separate what to do from how to do it. The logic of what to do with the payload is contained within the node. Meanwhile, the routing logic can live in the graph itself being based on the state of the payload. The state map becomes our single source of truth, and movement along the graph ends up being encoded in the transition logic. This logic is inherently decoupled from the logic of how each step is accomplished that's encoded within the implementation of each node.

When we treat each component is a self-contained unit with a single, well-defined responsibility, it becomes a composable block. Its internal logic can be complex, but its interface to the outside world is brutally simple: it accepts a defined input, performs its computation (which may involve calling other services or agents), and produces a defined output or side effect. Importantly, it manages its own internal state, but does not share that state directly with other blocks. This isolation is the first key to sanity, both for human developers and AI agents. An agent assigned to maintain or debug a component only needs to understand that component's specific context and contract, not the entire universe of the application. And functional programming provides exactly the tools we need to make this approach work at scale.

FP encourages building complex systems from simple, composable parts. Each handler is a simple function that can be combined in endless ways. FP makes all dependencies explicit through function parameters. There is no hidden global state or implicit context. This matches our explicit resource passing pattern. Monads, applicatives, and other FP patterns provide the tools for handling effects, errors, and asynchronous operations in a composable way.

Thus, FP architecture provides us with a perfect solution for keeping coding agents sane. We have immutable state flowing through pure transformations. Explicit dependency injection via function parameters. Composition of small, focused functions. Separation of decision from effect. These are all core FP principles that make it natural to structure code out of isolated components that can be reasoned about in isolation.

The beauty is that you get these benefits while remaining pragmatic. Functional languages like Clojure let us use mutable references where needed (for performance or integration), but keeps them isolated and managed. You get the safety and composability of pure FP with the practicality needed for real-world systems. This is why languages like Clojure, Erlang, and Haskell are such natural fits for LLMs—they provide the tools to manage the size of the context by default, rather than as an afterthought.

### The State Machine Graph as a Contract

Treating application's overall behavior as a state machine graph provides us with a master blueprint or contract. Nodes represent states of the computation or specific component invocations. Edges represent the allowed transitions, governed by conditions or the results of node computations.

A human can review and sign off on a high-level graph representing the data flow within the application. They are approving what each node does in a semantic sense and how the data flows through the graph, leaving the implementation details of each node to the coding agent. This graph becomes the unbreakable specification. The system cannot deviate from this defined workflow path, which guarantees a basic level of correctness and predictability.

Connecting the nodes is a dedicated orchestration layer. This layer's sole job is to route data between components according to the graph. It manages the flow, handles errors at the graph level (like retrying a node or taking a failure branch), and passes the necessary context from one node to the next. You could envision a specialized Conductor agent managing this layer, making decisions about flow while adhering to the graph's rules. Since the focus is on the state machine itself, the complexity the agent needs to manage is relatively small. The implementation details are abstracted behind the API of each node, and managed by the agent responsible for implementing its functionality.

The operational data is represented using a state map which encodes the current state of computation along with any metadata, such as history of the transitions through the graph, which can be invaluable for debugging complex workflows. Every transformation is explicit in the return value. There are no side channels modifying the state. Multiple agents or threads can safely read the state while transitions occur, because the current state never changes.

Each node can be tested in complete isolation. No need to set up complex state or mock entire systems. Pure functions are inherently reusable. The same `calculate-tax` function could be used in different workflows. When you compose pure functions, you get predictable results. If `f` and `g` are pure, then `(f (g x))` will always produce the same result for the same `x`. Even in cases where we may need to do side effects such as reading additional data, the data sources themselves can be passed in as parameters. Thus, side effects are decoupled from the actual business logic.

Functional programming treats functions as values, which gives us tremendous flexibility in how we compose our workflows. Logging, metrics, retry logic, and authentication can be added as middleware without modifying the core business logic. We can programmatically build workflows based on configuration or runtime conditions.

Referential transparency—the ability to replace a function call with its result without changing program behavior—is another superpower that makes this architecture manageable. You can understand each handler by looking only at its inputs and the functions it calls. Pure functions can be memoized, parallelized, or lazily evaluated without changing behavior. Extract common logic into helper functions without worrying about hidden dependencies.

### Layered Abstraction and Infinite Scale

What's more, the whole architecture is recursive by its very nature. A complete system built as a graph of components can itself be treated as a component. This new block exposes an API and can itself be dropped into a higher-level state machine graph. This creates a fractal architecture. You might have a low-level graph handling user authentication, which becomes a block in a mid-level graph managing a checkout process, which itself becomes a block in a high-level graph for an entire e-commerce platform. Scaling becomes a matter of composition as opposed to increasing complexity in a single codebase.

General-purpose languages and frameworks are too permissive for this model. Such a system needs to be built with intentional constraints. The ideal approach for defining components and graphs would be heavily influenced by functional programming and formal methods. For example, data driven schemas like Malli allow us to encode invariants directly ("this node's output must satisfy this schema," "this edge can only be taken if this condition is met"). The LLM agent cannot cheat here because it has to fulfill the contract by satisfying these constraints.

Once the high level specification is produced, the contracts can be handed down to the agents managing each node within the graph. The agents then must figure out how to implement the functionality that satisfies the contract representing the API of the component they're managing.

Erlang/OTP platform is particularly appealing here. Its model of millions of isolated, lightweight processes communicating via message passing is the perfect runtime for this architecture. Each node in the graph becomes a process. The OTP supervisors automatically handle component failures, restarts, and even hot-code upgrades, providing the resilience this distributed model requires. A Lisp-like REPL environment is equally useful for the development loop, allowing agents to test and iterate on the code in real-time within the live system.

### The Agent Synergy

I will argue that the architecture described above directly addresses key problems that we currently see with LLM assisted coding by separating the data flow from the logic of how the data is transformed.

Individual agents can own specific components. They can write, optimize, debug, and document their component, operating within a safe, bounded context. This prevents "context rot" where an agent loses coherence trying to understand a massive codebase, and avoids the problem of context growing over time. Each component has fixed scope and functionality.

A separate, strategic agent acts as the Conductor, managing the flow in the orchestration layer and possibly even suggesting optimizations to the graph itself. Here the scope is also limited because the graph can always be kept as the size the agent can work with effectively. If the overall scope of the application grows past that, then the graph can be partitioned. All the graph does is manage a set of rules that govern the flow of data between the components.

In essence, I'm proposing a future where software is built by assembling small and verifiable programs into human-approved workflows, running on a runtime designed for fault-tolerant concurrency, and maintained by specialized AI agents. It moves us from crafting intricate clockwork mechanisms to engineering with standardized, reliable bricks and a guaranteed assembly plan. The goal is to build a system where the software's structure inherently enforces correctness, scalability, and clear division of labor between human intent and automated execution.

The approach facilitates true separation of concerns with the pure transition functions containing all business logic that can be tested without mocks. Meanwhile, the execution functions contain all side effects and are trivial to verify. The state map makes every piece of data visible. At any point, the entire workflow state is a plain map that can be stored, inspected, or sent to another system. New workflows are built by adding new keys to the handler maps. You can even nest workflows by having one workflow's `:done` state trigger another workflow's start. Each handler is a small, context-bounded function with clear inputs and outputs. An AI agent could maintain, optimize, or even generate these handlers while respecting the protocol boundaries.

This architecture moves us from managing side effects to orchestrating pure decisions, where the hardest parts of your business logic become simple data transformations, and all complexity emerges from the composition of simple parts.

To prove this workflow, we’ll use https://github.com/yogthos/maestro as our runtime "railway" and **Claude Code** (powered by https://github.com/charmbracelet/crush ) as our specialized development-time construction crew. The goal is to create a system where the high-level architecture is a data-driven contract that no agent can break, while the individual components are implemented in isolation.

### The Implementation Plan

---

## Phase 1: The Blueprint (Human-Driven)

Before the agents start coding, you must define the "Track" of your railway. You will create a `workflow.clj` file that uses Maestro to define the state machine.

* **Action:** Define a Maestro map where each state represents a discrete transformation.
* **Contract:** Use **Malli** schemas within the Maestro metadata to define exactly what the `:data` map must look like before and after each node.
* **Isolation:** At this stage, the transition functions are just "shells" (empty functions) that return the input data.

```clojure
;; workflow.clj
(def my-workflow
  {:states {:start   {:handler validate-input :next :processing}
            :processing {:handler transform-data :next :end}
            :end     {:handler cleanup-node}}
   :schema {:input  [:map [:user-id :string]]
            :output [:map [:status :keyword]]}})

```

---

## Phase 2: The Construction Crew (Crush + Claude Code)

Once the blueprint is set, you bring in the agents. Instead of giving one agent the whole project, you use **Crush** to create "Workspaces" for each Maestro state.

### Step 1: Initialize the Workspace

Use Crush to create a focused context for the agent.
`crush session start --name validate-input-node`
This limits the agent's focus to the specific function `validate-input`.

### Step 2: The Execution Command

You run a specialized **Claude Code** command that targets the specific node.

> `claude -p "Implement the 'validate-input' function. It must satisfy the Malli schema in workflow.clj. Use the REPL to verify it works in isolation. Do not modify the Maestro graph structure."`

### Step 3: TDD in the Loop

The agent should follow a strict loop within its Crush-managed workspace:

1. **Read** the Maestro node metadata.
2. **Generate** a test case that mirrors the `:input` schema.
3. **Write** the Clojure code.
4. **Eval** in the REPL (using `clj-nrepl-eval` or similar Crush-integrated tools).
5. **Commit** only when the node passes its isolated contract.

---

## Phase 3: Integration & Proof of Workflow

Once all nodes are implemented, you run the full Maestro engine.

* **The Orchestrator:** Load the `workflow.clj` into a Clojure REPL.
* **The Run:** Execute `(maestro/run my-workflow {:user-id "123"})`.
* **Verification:** Because each transition was a pure function verified by an agent against a schema, the integration should be seamless. The "State Map" trace in Maestro will show you exactly how the data evolved at every step.

### Why this works for Claude Code:

1. **Zero Context Rot:** The agent never sees the code for `:processing` while it's working on `:start`. Its context window stays nearly empty, maximizing its reasoning capability.
2. **Schema Enforcement:** Malli acts as the "unit test" for the architecture. If the agent returns the wrong data shape, Maestro will fail at the gate, preventing the error from propagating.
3. **Reproducibility:** If a node fails, you can hand the exact `:input` map (extracted from the Maestro trace) to a new agent session and say "Fix this."

This example gets right to the heart of why this architecture is so powerful for agents: it turns a "web app" into a series of boring, predictable, and highly verifiable data transformations. In a typical Clojure/Maestro setup, the web server (like Reitit or Ring) is just the entry point that injects the initial "payload" into our railway.

Let's look at a **User Onboarding & Profile** workflow. Instead of a messy controller with hidden side effects, we define a Maestro map where each step is a pure decision or a bounded effect.

---

## The Maestro Blueprint

In this model, the "State Map" is the payload. It enters the system as a request and exits as a response. Every station in between adds to or modifies this map.

```clojure
(def user-flow
  {:states 
   {:start            {:handler :auth/parse-request
                       :next    {:success :auth/validate-session
                                 :fail    :response/error}}
    
    :auth/validate-session {:handler :auth/check-db
                            :next    {:authorized   :user/fetch-profile
                                      :unauthorized :response/redirect-login}}
    
    :user/fetch-profile    {:handler :db/get-user-data
                            :next    {:found     :ui/render-home
                                      :not-found :response/error}}
    
    :ui/render-home        {:handler :view/home-page
                            :next    :terminal}}})

```

---

## The Construction Contract (Malli)

This is where we trap the agent. We don't just tell Claude Code to "write a login handler." We give it a Malli schema that acts as a physical boundary.

* **Node:** `:auth/parse-request`
* **Input Schema:** `[:map [:http-request any?]]`
* **Output Schema:** `[:map [:user-id [:string {:min 3}]] [:auth-token :string]]`

When Claude Code spins up via **Crush**, it sees this schema. It knows that if it tries to return a map without a `:user-id`, the Maestro runtime will throw an exception before the next node even sees the data. This prevents the "null pointer" style errors that usually happen when agents get confused about data shapes.

---

## Practical Implementation Steps

### 1. The Entry Point (The Station Master)

Your HTTP route (e.g., `/home`) doesn't contain logic. It simply initializes the Maestro state machine with the request data.

```clojure
(defn home-handler [request]
  (let [result (maestro/execute user-flow {:http-request request})]
    (or (:http-response result) 
        {:status 500 :body "Internal Server Error"})))

```

### 2. The Pure Transformation (The Worker)

An agent assigned to `:ui/render-home` doesn't need to know how the database works or how the session was validated. It only needs to know that by the time the data reaches it, the state map *guarantees* a valid user object exists. Its only job is to transform that user data into HTML/Hiccup.

### 3. Side Effects as Data

To keep the handlers "pure" for the agent, we pass "Capabilities" in the state map. If `:auth/check-db` needs to talk to Postgres, we don't let the agent hardcode a DB connection. Instead, the initial state map includes a `:db-component`. This allows us to mock the entire database during the construction phase in Crush by simply swapping that component for a simple atom or a local file.

---

## Why this scales

If you suddenly need to add Two-Factor Authentication (2FA), you don't rewrite your auth logic. You simply insert a new node into the Maestro graph:
`... :auth/validate-session -> :auth/check-2fa -> :user/fetch-profile ...`

The agent building the 2FA node has a tiny context: "Take a session, check a code, return success or fail." It doesn't need to touch the home page code or the login code. This is **Fractal Scaling**: complexity is managed by the "Connective Tissue" (the graph), not the "Cells" (the functions).

---

# The Railway Architecture Guide

## Project Philosophy

This project uses a **Fractal Railway Architecture**. The system is a directed graph of pure data transformations managed by **Maestro**.

* **The Graph is Law:** All transitions must be defined in the Maestro map.
* **Isolation is Sanity:** Agents work on one node at a time.
* **Schemas are Contracts:** Every node must satisfy its Malli input/output schemas.
* **Development via Crush:** Use Crush to manage isolated sessions and REPL interactions.

---

## Phase 1: The Infrastructure (Skeleton & Schema)

The goal is to define the "tracks" before any "trains" (logic) run on them.

### Tasks

1. Create `src/railway/specs.cljc` to house Malli schemas for the global state map.
2. Create `src/railway/core.clj` with a Maestro workflow map using `:noop` handlers.

### TDD Requirements

* **Test:** Define a test that validates the Maestro map structure itself.
* **Test:** Validate that an empty state map fails the initial schema check.

---

## Phase 2: The Authentication Cells

Implement the nodes responsible for identifying the user.

### Nodes to Implement

* `:auth/parse-request`: Extracts credentials from the Ring request map.
* `:auth/validate-session`: Checks credentials against the database component.

### TDD Requirements (Per Node)

* **Isolated Test:** Create a test file for the namespace (e.g., `test/railway/auth_test.clj`).
* **Contract Test:** Use `malli.core/validate` to ensure the function output matches the node's output schema given a valid input mock.
* **Failure Test:** Ensure the handler returns a `:fail` transition if the input is malformed.

---

## Phase 3: The Data & UI Cells

Implement the nodes that fetch business logic and transform it into view data.

### Nodes to Implement

* `:user/fetch-profile`: Queries the DB component using the validated `:user-id`.
* `:ui/render-home`: Transforms the user profile data into Hiccup/HTML.

### TDD Requirements

* **Mocking:** Pass a "mock DB" into the state map. The agent must not hardcode database connections.
* **Pure Logic Test:** The `render-home` test should simply assert that given a specific user map, a specific Hiccup vector is returned.

---

## Phase 4: Integration & Railway Run

The final phase where the individual "bricks" are assembled into the live system.

### Tasks

1. Connect the Maestro workflow to a **Reitit** or **Ring** entry point.
2. Implement a global error handler for the `:terminal` failure states.

### TDD Requirements

* **End-to-End Test:** Start the system with a mock request. Trace the Maestro execution to ensure it hits every expected node in the correct order.
* **Schema Enforcement Test:** Intentionally pass bad data through an early node and verify that Maestro stops the execution before it reaches the UI layer.

---

## Agent Workflow Instructions

When implementing a node, follow this loop:

1. **Initialize:** `crush session start --name <node-name>`
2. **Context:** Read only the relevant namespace and the `workflow.clj` file.
3. **Red:** Write an isolated test case for the handler based on the Malli schema.
4. **Green:** Implement the handler logic.
5. **Refactor:** Ensure no side effects are "leaking." All effects must be requested via the state map.
6. **Submit:** `crush session stop`

---

This guide gives us a repeatable protocol. Since the agent's work is gated by the schemas, we can effectively "parallelize" the construction if we wanted to. Would you like me to generate the initial **Malli schemas** for the `:auth/parse-request` and `:user/fetch-profile` nodes to get the ball rolling?