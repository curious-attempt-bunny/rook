 (ns magic)

 (defn index
   "In this test, the magic-value is provided as an arg-resolver."
   [magic-value]
   magic-value)

 (defn show
   [id ^:magic extra]
   (str id " -- " extra))