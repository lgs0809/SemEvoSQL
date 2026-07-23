-- One versioned tokenizer runs on both stored text and query text. No external extension/dictionary required.
CREATE FUNCTION qw_semantic_tokenize_v1(value text) RETURNS text
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
DECLARE
  piece text;
  current_char text;
  previous_han text;
  word text;
  output text[] := ARRAY[]::text[];
  code integer;
BEGIN
  FOR piece IN SELECT regexp_split_to_table(lower(normalize(value, NFKC)), '[^[:alnum:]_]+') LOOP
    previous_han := '';
    word := '';
    FOR i IN 1..char_length(piece) LOOP
      current_char := substr(piece, i, 1);
      code := ascii(current_char);
      IF code BETWEEN 13312 AND 40959 OR code BETWEEN 131072 AND 205743 OR code BETWEEN 63744 AND 64255 THEN
        IF word <> '' THEN output := array_append(output, word); word := ''; END IF;
        output := array_append(output, current_char);
        IF previous_han <> '' THEN output := array_append(output, previous_han || current_char); END IF;
        previous_han := current_char;
      ELSE
        word := word || current_char;
        previous_han := '';
      END IF;
    END LOOP;
    IF word <> '' THEN output := array_append(output, word); END IF;
  END LOOP;
  RETURN array_to_string(output, ' ');
END
$$;

ALTER TABLE qw_semantic_retrieval_document
  ADD COLUMN lexical_tokenizer_version varchar(64) NOT NULL DEFAULT 'nfkc-han-unigram-bigram-v1',
  ADD COLUMN lexical_search_vector tsvector GENERATED ALWAYS AS
    (to_tsvector('simple'::regconfig, qw_semantic_tokenize_v1(lexical_text))) STORED;
CREATE INDEX idx_qw_semantic_document_fts ON qw_semantic_retrieval_document USING gin(lexical_search_vector);
CREATE INDEX idx_qw_semantic_document_scope ON qw_semantic_retrieval_document(project_id,project_version_id,catalog_hash,id);
