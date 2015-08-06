(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [cljs.tools.reader :refer [read *wrap-value-and-add-metadata?*]]
            [cljs.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.string :refer [split-lines join replace triml]]
            [clojure.walk :refer [postwalk]]
            [goog.events :as events]
            [rangy.core]
            [rangy.textrange]
            [schema.core :refer [maybe either Any Str Int Keyword Bool]])
  (:require-macros [schema.core :refer [defn with-fn-validation]]
                   [cljs.core.async.macros :refer [go]]))

(defn read-safe :- (maybe (either Any js/Error))
  "Returns either a form or an exception object, or nil if EOF is reached."
  [reader :- js/Object]
  (try
    (binding [*wrap-value-and-add-metadata?* true]
      (read reader false nil))
    (catch js/Error e e)))

(defn read-forms :- [Any]
  "Returns a list of values representing each top-level form."
  [s :- Str]
  (let [reader (indexing-push-back-reader s)]
    (repeatedly (partial read-safe reader))))

(defn tag-list :- [{Keyword Any}]
  "Returns a list of maps describing each tag."
  ([token :- Any]
    (tag-list token 0 0 0 0))
  ([token :- Any
    parent-level :- Int
    parent-adjust :- Int
    parent-column :- Int
    parent-line :- Int]
    (flatten
      (cond
        ; an error
        (instance? js/Error token)
        [(assoc (.-data token) :message (.-message token) :error? true :level parent-level)]
        
        ; a key-value pair from a map
        (and (coll? token) (nil? (meta token)))
        (map #(tag-list % parent-level parent-adjust parent-column parent-line) token)
        
        ; a valid token
        :else
        (let [{:keys [line column end-line end-column wrapped?]} (meta token)
              value (if wrapped? (first token) token)]
          [; begin tag
           {:line line :column column :value value}
           (if (coll? value)
             (let [delimiter-size (if (set? value) 2 1)
                   new-level (+ parent-level
                                (if (not= parent-line line)
                                  parent-adjust
                                  0))
                   new-adjust (if (list? value) 2 delimiter-size)
                   new-column (max (dec column)
                                   parent-column
                                   0)]
               [; open delimiter tags
                {:line line :column column :delimiter? true}
                {:end-line line :end-column (+ column delimiter-size) :level (+ new-level new-adjust new-column)}
                ; child tags
                (map #(tag-list % new-level new-adjust new-column line) value)
                ; close delimiter tags
                {:line end-line :column (dec end-column) :delimiter? true}
                {:end-line end-line :end-column end-column}])
             [])
           ; end tag
           {:end-line end-line :end-column end-column :level (+ parent-level parent-adjust parent-column)}])))))

(defn indent-list :- [{Keyword Any}]
  "Returns a list of maps describing each indent tag."
  [tags :- [{Keyword Any}]
   line-count :- Int]
  (let [tags-by-line (group-by #(or (:line %) (:end-line %)) tags)]
    (loop [i 1
           current-level 0
           result []]
      (if (<= i line-count)
        (recur (inc i)
               (or (some-> (get tags-by-line i) last :level)
                   current-level)
               (conj result
                     {:line i
                      :column 1
                      :level current-level
                      :indent? true}))
        result))))

(defn tag->html :- Str
  "Returns an HTML string for the given tag description."
  [tag :- {Keyword Any}]
  (cond
    (:indent? tag) (str "<span class='indent'>"
                        (join (repeat (:level tag) " "))
                        "</span>")
    (:delimiter? tag) "<span class='delimiter'>"
    (:error? tag) (or (.log js/console (:message tag))
                      "<span class='error'></span>")
    (:line tag) (let [value (:value tag)]
                  (cond
                    (symbol? value) "<span class='symbol'>"
                    (list? value) "<span class='collection list'>"
                    (vector? value) "<span class='collection vector'>"
                    (map? value) "<span class='collection map'>"
                    (set? value) "<span class='collection set'>"
                    (number? value) "<span class='number'>"
                    (string? value) "<span class='string'>"
                    (keyword? value) "<span class='keyword'>"
                    (nil? value) "<span class='nil'>"
                    (contains? #{true false} value) "<span class='boolean'>"
                    :else "<span>"))
    (:end-line tag) "</span>"
    :else "<span>"))

(defn split-lines-without-indent :- [Str]
  "Splits the string into lines while removing all indentation."
  [s :- Str]
  (let [lines (map triml (split-lines (str s " ")))
        last-line (last lines)]
    (conj (vec (butlast lines))
          (subs last-line 0 (dec (count last-line))))))

(defn add-html-to-line :- Str
  "Returns the given line with html added."
  [line :- Str
   tags-for-line :- [{Keyword Any}]]
  (let [get-column #(or (:column %) (:end-column %))
        tags-for-line (sort-by get-column tags-for-line)
        html-per-column (sequence (comp (partition-by get-column)
                                        (map #(join (map tag->html %))))
                                  tags-for-line)
        columns (set (map get-column tags-for-line))
        segments (loop [i 0
                        segments []
                        current-segment []]
                   (if-let [c (get line i)]
                     (if (contains? columns (inc i))
                       (recur (inc i)
                              (conj segments current-segment)
                              [c])
                       (recur (inc i)
                              segments
                              (conj current-segment c)))
                     (map join (conj segments current-segment))))]
    (join (interleave segments (concat html-per-column (repeat ""))))))

(defn add-html :- Str
  "Returns the given string with html added."
  [s :- Str]
  (let [lines (split-lines-without-indent s)
        tags (sequence (comp (take-while some?) (mapcat tag-list))
                       (read-forms (join \newline lines)))
        tags (concat (indent-list tags (count lines)) tags)
        get-line #(or (:line %) (:end-line %))
        tags-by-line (group-by get-line tags)]
    (->> (interleave (iterate inc 1) lines)
         (sequence (comp (partition-all 2)
                         (map (fn [[i line]]
                                (add-html-to-line line (get tags-by-line i))))))
         (join "<br/>"))))

(def rainbow-colors ["aqua" "brown" "cornflowerblue"  "fuchsia" "gold"
                     "hotpink" "lime" "orange" "plum" "tomato"])

(defn rainbow-delimiters :- {js/Element Str}
  "Returns a map of elements and colors."
  [parent :- js/Element
   level :- Int]
  (apply merge
         {}
         (for [elem (-> parent .-children array-seq)]
           (cond
             (-> elem .-classList (.contains "delimiter"))
             {elem (get rainbow-colors (mod level (count rainbow-colors)))}
             (-> elem .-classList (.contains "collection"))
             (apply merge {} (rainbow-delimiters elem (inc level)))
             :else
             {}))))

(defn refresh!
  "Refreshes the contents of the editor."
  [editor :- js/Element
   advance-caret? :- Bool]
  (let [sel (-> js/rangy .getSelection (.saveCharacterRanges editor))]
    (set! (.-innerHTML editor) (add-html (.-innerText editor)))
    (when advance-caret?
      (let [range (.-characterRange (aget sel 0))
            text (.-innerText editor)
            position (loop [i (.-start range)]
                       (if (= " " (aget text i))
                         (recur (inc i))
                         i))]
        (set! (.-start range) position)
        (set! (.-end range) position)))
    (-> js/rangy .getSelection (.restoreCharacterRanges editor sel)))
  (doseq [[elem color] (rainbow-delimiters editor -1)]
    (set! (-> elem .-style .-color) color)))

(defn init! []
  (.init js/rangy)
  (let [editor (.querySelector js/document ".paren-soup")
        changes (chan)]
    (set! (.-spellcheck editor) false)
    (refresh! editor false)
    (events/removeAll editor)
    (events/listen editor "keydown" #(put! changes %))
    (go (while true
          (let [event (<! changes)
                editor (.-currentTarget event)
                code (.-keyCode event)]
            (when-not (contains? #{37 38 39 40} code)
              (refresh! editor (= 13 code))))))))

(defn init-with-validation! []
  (with-fn-validation (init!)))

#_(init!)
