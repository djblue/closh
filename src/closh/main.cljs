(ns closh.main
  (:require [clojure.tools.reader]
            [clojure.tools.reader.impl.commons]
            [clojure.pprint :refer [pprint]]
            [clojure.string]
            ; [lumo.io]
            [lumo.repl]
            [closh.parser]
            [closh.builtin]
            [closh.eval :refer [execute-text]]
            [closh.core :refer [handle-line]]
            [closh.history :refer [init-database add-history]])
  (:require-macros [alter-cljs.core :refer [alter-var-root]]
                   [closh.reader :refer [patch-reader]]
                   [closh.core :refer [sh]]))

(enable-console-print!)

(def ^:no-doc readline (js/require "readline"))
(def ^:no-doc child-process (js/require "child_process"))
(def ^:no-doc fs (js/require "fs"))
(def ^:no-doc os (js/require "os"))
(def ^:no-doc path (js/require "path"))

(def readline-tty-write readline.Interface.prototype._ttyWrite)

(def initial-readline-state {:mode :input})
(def readline-state (atom initial-readline-state))

(defn load-init-file
  "Loads init file."
  [init-path]
  (when (try (-> (fs.statSync init-path)
                 (.isFile))
             (catch :default _))
    (try (lumo.repl/execute-path init-path {})
         (catch :default e
           (js/console.error "Error while loading " init-path ":\n" e)))))

(defn restore-previous-state [state]
  (assoc state
    :history-state nil
    :mode :input
    :prompt (:previous-prompt state)
    :previous-line nil
    :previous-prompt nil
    :query nil
    :failed-search false))

(defn activate-search-state [state rl search-mode]
  (if (:history-state state)
    (assoc state :search-mode search-mode
                 :mode (if (= search-mode :prefix) (:mode state) :search))
    (let [query (subs (.-line rl) 0 (.-cursor rl))
          mode (if (and (= search-mode :prefix)
                        (empty? query))
                 :input
                 :search)]
      (assoc state
        :mode mode
        :search-mode search-mode
        :query query
        :line ""
        :cursor 0
        :previous-prompt (.-_prompt rl)
        :previous-line (.-line rl)))))

(defn render-line [rl {:keys [line cursor prompt mode search-mode query failed-search]}]
  (when-not (nil? line) (aset rl "line" line))
  (when-not (nil? cursor) (aset rl "cursor" cursor))
  (when-let [p (if (= mode :search)
                 (let [kind (case search-mode
                              :prefix "history-prefix-search"
                              :substr "history-search"
                              "unknown-type-of-search")
                       label (if failed-search (str "failed " kind) kind)]
                   (str "(" label ")`" query "': "))
                 prompt)]
    (aset rl "_prompt" p))
  (._refreshLine rl))

(defn prompt
  "Prints prompt to a readline instance."
  [rl]
  (doto rl
    (.setPrompt (execute-text "(closh-prompt)"))
    (.prompt true)))

;; TODO: Potencial race condition if latter history call returns before the previous one
;; Maybe some loading indicator?
(defn search-history-prev [{:keys [query history-state search-mode] :as state} rl]
  (closh.history/search-history-prev query history-state search-mode
    (fn [err data]
      (when err (js/console.log "Error searching history:" err))
      (swap! readline-state
        #(if-let [[line index] data]
           (assoc % :history-state index
                    :line line
                    :cursor (count line)
                    :failed-search false)
           (assoc % :mode :search ;; Make sure we are in search mode when search fails to display user a message
                    :failed-search true)))
      (render-line rl @readline-state)))
  state)

(defn search-history-next [{:keys [query history-state search-mode] :as state} rl]
  (closh.history/search-history-next query history-state search-mode
    (fn [err data]
      (when err (js/console.log "Error searching history:" err))
      (swap! readline-state
        #(if-let [[line index] data]
           (assoc % :history-state index
                    :line line
                    :cursor (count line)
                    :failed-search false)
           (let [line (or (:previous-line %) "")
                 cursor (count line)]
             (assoc (restore-previous-state %)
                    :cursor cursor
                    :line line))))
      (render-line rl @readline-state)))
  state)

(defn key-value [key]
  ;; escape seems to come with meta always switched on, so lets strip it for now
  (if (= (.-name key) "escape")
    "escape"
    (->>
      [(when (.-ctrl key) "ctrl")
       (when (.-meta key) "meta")
       (when (.-shift key) "shift")
       (.-name key)]
      (filter identity)
      (clojure.string/join "-"))))

(defn handle-keypress [{:keys [query] :as state} rl c key]
  (case (:mode state)
   :input
   (case (key-value key)
     "up" (-> state
            (activate-search-state rl :prefix)
            (search-history-prev rl))
     "down" (if (:history-state state)
              (-> state
                (activate-search-state rl :prefix)
                (search-history-next rl))
              state)
     "ctrl-r" (-> state
                (activate-search-state rl :substr)
                (search-history-prev rl))
     nil)

   :search
   (case (key-value key)
     "up" (search-history-prev state rl)
     "down" (search-history-next state rl)
     ;; Accept current line
     "tab" (restore-previous-state state)
     ;; Accept and execute current line
     ;; TODO: Execute
     "enter" (restore-previous-state state)
     ;; Cancel search
     "escape" (let [line (or (:previous-line state) "")
                    cursor (.-length line)]
                (assoc (restore-previous-state state)
                       :cursor cursor
                       :line line))
     ;; Cancel search and reset line input
     "ctrl-c" (assoc (restore-previous-state state)
                     :line ""
                     :cursor 0)
     ;; Search for previous entry (switches to substr search mode if necessary)
     "ctrl-r" (search-history-prev (assoc state :search-mode :substr) rl)
     ;; Search for next entry (switches to substr search mode if necessary)
     "ctrl-s" (search-history-next (assoc state :search-mode :substr) rl)
     ;; Default case - update search query based on typed character
     (if-let [q (when (not (or (.-meta key) (.-ctrl key)))
                  (if (and (not (.-shift key)) (= (.-name key) "backspace"))
                    (.slice query 0 -1)
                    (str query c)))]
       (when (not= query q)
         (let [next-state (assoc state :query q
                                       :history-state nil)]
           (search-history-prev next-state rl)))))

   nil))

(defn -main
  "Starts closh REPL with prompt and readline."
  []
  (patch-reader)
  (load-init-file (path.join (os.homedir) ".closhrc"))
  (let [rl (.createInterface readline
             #js{:input js/process.stdin
                 :output js/process.stdout
                 :prompt "$ "})]
    (aset rl "_ttyWrite"
      (fn [c key]
        (this-as self
          (if-let [state (handle-keypress @readline-state self c key)]
            (do
              (reset! readline-state state)
              (render-line rl state))
            (.call readline-tty-write self c key)))))
    (init-database
     (fn [err]
       (if err
         (do (js/console.error "Error initializing history database:" err)
             (js/process.exit 1)))))
    (doto rl
      (.on "line"
        (fn [input]
          (.pause rl)
          (when-not (or (clojure.string/blank? input)
                        (re-find #"^\s+" input))
              (reset! readline-state initial-readline-state)
              (add-history input (js/process.cwd)
                (fn [err] (when err (js/console.error "Error saving history:" err))))
            (try
              (let [result (handle-line input execute-text)]
                (when-not (or (nil? result)
                              (instance? child-process.ChildProcess result)
                              (and (seq? result)
                                   (every? #(instance? child-process.ChildProcess %) result)))
                  (.write js/process.stdout (with-out-str (pprint result)))))
              (catch :default e
                (js/console.error e))))
          (prompt rl)
          (.resume rl)))
      (.on "close" #(.exit js/process 0))
      (prompt))))

(set! *main-cli-fn* -main)
