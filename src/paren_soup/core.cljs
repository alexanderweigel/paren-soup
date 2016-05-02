(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [clojure.string :refer [join replace]]
            [goog.events :as events]
            [goog.string :refer [format]]
            [cljsjs.rangy-core]
            [cljsjs.rangy-textrange]
            [schema.core :refer [maybe either Any Str Int Keyword Bool]]
            [mistakes-were-made.core :as mwm]
            [tag-soup.core :as ts]
            [html-soup.core :as hs]
            [cross-parinfer.core :as cp])
  (:require-macros [schema.core :refer [defn with-fn-validation]]
                   [cljs.core.async.macros :refer [go]]))

(defn show-error!
  "Shows a popup with an error message."
  [parent-elem :- js/Object
   event :- js/Object]
  (let [elem (.-target event)
        x (.-clientX event)
        y (.-clientY event)]
    (let [popup (.createElement js/document "div")]
      (aset popup "textContent" (-> elem .-dataset .-message))
      (aset (.-style popup) "top" (str y "px"))
      (aset (.-style popup) "left" (str x "px"))
      (aset popup "className" "error-text")
      (.appendChild parent-elem popup))))

(defn hide-errors!
  "Hides all error popups."
  [parent-elem :- js/Object]
  (doseq [elem (-> parent-elem (.querySelectorAll ".error-text") array-seq)]
    (.removeChild parent-elem elem)))

(defn results->html :- Str
  "Returns HTML for the given eval results."
  [elems :- [js/Object]
   results :- [Any]
   top-offset :- Int]
  (loop [i 0
         offset 0
         evals (transient [])]
    (if-let [elem (get elems i)]
      (let [top (-> elem .getBoundingClientRect .-top (- top-offset) (+ (.-scrollY js/window)))
            height (-> elem .getBoundingClientRect .-bottom (- top-offset) (+ (.-scrollY js/window)) (- top))
            res (get results i)]
        (recur (inc i)
               (+ offset height)
               (conj! evals
                 (format
                   "<div class='%s' style='top: %spx; height: %spx; min-height: %spx'>%s</div>"
                   (if (array? res) "result error" "result")
                   top
                   height
                   height
                   (some-> (if (array? res) (first res) res)
                           hs/escape-html-str)))))
      (join (persistent! evals)))))

(defn get-collections :- [js/Object]
  "Returns collections from the given DOM node."
  [content :- js/Object]
  (vec (for [elem (-> content .-children array-seq)
             :let [classes (.-classList elem)]
             :when (or (.contains classes "collection")
                       (.contains classes "symbol"))]
         elem)))

(def ^:const rainbow-count 10)

(defn rainbow-delimiters :- {js/Object Str}
  "Returns a map of elements and class names."
  [parent :- js/Object
   level :- Int]
  (apply merge
         {}
         (for [elem (-> parent .-children array-seq)]
           (cond
             (-> elem .-classList (.contains "delimiter"))
             {elem (str "rainbow-" (mod level rainbow-count))}
             (-> elem .-classList (.contains "collection"))
             (apply merge {} (rainbow-delimiters elem (inc level)))
             :else
             {}))))

(defn line-numbers :- Str
  "Adds line numbers to the numbers."
  [line-count :- Int]
  (join (for [i (range line-count)]
          (str "<div>" (inc i) "</div>"))))

(defn get-selection :- js/Object
  "Returns the objects related to selection for the given element."
  [content :- js/Object]
  (let [selection (.getSelection js/rangy)
        ranges (.saveCharacterRanges selection content)
        char-range (some-> ranges (aget 0) (aget "characterRange"))]
    {:selection selection :ranges ranges :char-range char-range}))

(defn get-cursor-position :- [Int]
  "Returns the cursor position."
  [content :- js/Object]
  (if-let [range (some-> content get-selection :char-range)]
    [(aget range "start") (aget range "end")]
    [0 0]))

(defn set-cursor-position!
  "Moves the cursor to the specified position."
  [content :- js/Object
   &
   [start-pos :- Int
    end-pos :- Int]]
  (let [{:keys [selection ranges char-range]} (get-selection content)]
    (when (and selection ranges char-range)
      (aset char-range "start" start-pos)
      (aset char-range "end" (or end-pos start-pos))
      (.restoreCharacterRanges selection content ranges))))

(defn update-editor!
  "Adds error messages and rainbow delimiters to the editor."
  [content :- js/Object
   events-chan :- js/Object]
  ; set the mouseover events for errors
  (doseq [elem (-> content (.querySelectorAll ".error") array-seq)]
    (events/listen elem "mouseenter" #(put! events-chan %))
    (events/listen elem "mouseleave" #(put! events-chan %)))
  ; add rainbow delimiters
  (doseq [[elem class-name] (rainbow-delimiters content -1)]
    (.add (.-classList elem) class-name)))

(defn refresh-content! :- {Keyword Any}
  "Refreshes the contents."
  [content :- js/Object
   events-chan :- Any
   state :- {Keyword Any}]
  (let [state (if (= \newline (last (:text state)))
                (assoc state :text (str (:text state) \newline))
                state)
        state (if (:indent-type state)
                (cp/add-indent state)
                state)
        html-text (hs/code->html (:text state))
        [start-pos end-pos] (:cursor-position state)]
    (set! (.-innerHTML content) html-text)
    (set-cursor-position! content start-pos end-pos)
    (update-editor! content events-chan)
    state))

(defn refresh-numbers!
  "Refreshes the line numbers."
  [numbers :- js/Object
   line-count :- Int]
  (set! (.-innerHTML numbers) (line-numbers line-count)))

(defn refresh-instarepl!
  "Refreshes the InstaREPL."
  [instarepl :- js/Object
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object]
  (let [elems (get-collections content)
        forms (into-array (map #(-> % .-textContent (replace \u00a0 " ")) elems))]
    (set! (.-onmessage eval-worker)
          (fn [e]
            (let [results (.-data e)
                  top-offset (-> instarepl .getBoundingClientRect .-top (+ (.-scrollY js/window)))]
              (when (some-> elems first .-parentNode)
                (set! (.-innerHTML instarepl)
                      (results->html elems results top-offset))))))
    (.postMessage eval-worker forms)))

(defn br->newline!
  "Replaces <br> tags with newline chars."
  [content :- js/Object]
  (let [html (.-innerHTML content)]
    (set! (.-innerHTML content)
          (if (>= (.indexOf html "<br>") 0)
            (-> html (replace "<br>" \newline) (replace "</br>" ""))
            (-> html (replace "<div>" \newline) (replace "</div>" ""))))))

(defn init-state! :- {Keyword Any}
  "Returns the editor's state after sanitizing it."
  [content :- js/Object]
  (let [pos (get-cursor-position content)
        _ (br->newline! content)
        text (.-textContent content)]
    {:cursor-position pos
     :text text}))

(defn refresh! :- {Keyword Any}
  "Refreshes everything."
  [instarepl :- (maybe js/Object)
   numbers :- (maybe js/Object)
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object
   state :- {Keyword Any}]
  (let [state (refresh-content! content events-chan state)]
    (some-> numbers (refresh-numbers! (inc (count (re-seq #"\n" (:text state))))))
    (some-> instarepl (refresh-instarepl! content events-chan eval-worker))
    state))

(defn undo-or-redo? [e]
  (and (or (.-metaKey e) (.-ctrlKey e))
       (= (.-keyCode e) 90)))

(defn tab? [e]
  (= (.-keyCode e) 9))

(defn init! []
  (.init js/rangy)
  (doseq [paren-soup (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (let [instarepl (.querySelector paren-soup ".instarepl")
          numbers (.querySelector paren-soup ".numbers")
          content (.querySelector paren-soup ".content")
          events-chan (chan)
          eval-worker (when instarepl (js/Worker. "paren-soup-compiler.js"))
          edit-history (mwm/create-edit-history)]
      (set! (.-spellcheck paren-soup) false)
      (when-not content
        (throw (js/Error. "Can't find a div with class 'content'")))
      (->> (init-state! content)
           (cp/add-parinfer :paren)
           (refresh! instarepl numbers content events-chan eval-worker)
           (mwm/update-edit-history! edit-history))
      (events/removeAll content)
      (events/listen content "keydown" (fn [e]
                                         (put! events-chan e)
                                         (when (or (undo-or-redo? e) (tab? e))
                                           (.preventDefault e))))
      (events/listen content "keyup" #(put! events-chan %))
      (events/listen content "mouseup" #(put! events-chan %))
      (events/listen content "cut" #(put! events-chan %))
      (events/listen content "paste" #(put! events-chan %))
      (go (while true
            (let [event (<! events-chan)]
              (case (.-type event)
                "keydown"
                (when (undo-or-redo? event)
                  (if (.-shiftKey event)
                    (when-let [state (mwm/redo! edit-history)]
                      (refresh! instarepl numbers content events-chan eval-worker state))
                    (when-let [state (mwm/undo! edit-history)]
                      (refresh! instarepl numbers content events-chan eval-worker state))))
                "keyup"
                (cond
                  (contains? #{37 38 39 40} (.-keyCode event))
                  (mwm/update-cursor-position! edit-history (get-cursor-position content))
                  
                  (not (or (contains? #{16 ; shift
                                        17 ; ctrl
                                        18 ; alt
                                        91 93} ; meta
                                      (.-keyCode event))
                           (.-ctrlKey event)
                           (.-metaKey event)))
                  (let [state (init-state! content)]
                    (->> (case (.-keyCode event)
                           13 (assoc  state :indent-type :return)
                           9 (assoc state :indent-type (if (.-shiftKey event) :back :forward))
                           (cp/add-parinfer :indent state))
                         (refresh! instarepl numbers content events-chan eval-worker)
                         (mwm/update-edit-history! edit-history))))
                "cut"
                (->> (init-state! content)
                     (cp/add-parinfer :both)
                     (refresh! instarepl numbers content events-chan eval-worker)
                     (mwm/update-edit-history! edit-history))
                "paste"
                (->> (init-state! content)
                     (cp/add-parinfer :both)
                     (refresh! instarepl numbers content events-chan eval-worker)
                     (mwm/update-edit-history! edit-history))
                "mouseup"
                (mwm/update-cursor-position! edit-history (get-cursor-position content))
                "mouseenter"
                (show-error! paren-soup event)
                "mouseleave"
                (hide-errors! paren-soup)
                nil)))))))

(defn init-debug! []
  (.log js/console (with-out-str (time (with-fn-validation (init!))))))

(set! (.-onload js/window) init!)
