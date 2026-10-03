package com.corebanking.integration.core.nach;

/**
 * A layout of NACH debit presentation and response files. NPCI defines the ACH debit transaction, but the file a
 * lender exchanges with its sponsor bank follows that bank's own layout (column order, widths, header and trailer
 * records, file naming, sometimes encryption). Each sponsor bank's layout is one implementation of this
 * interface; the product ships {@link GenericNachFormat} only.
 */
public interface NachFileFormat {

    /** Code stored with every file, e.g. GENERIC. */
    String code();

    /** FIXED or CSV. */
    String encoding();

    String fileExtension();

    String render(NachFile.Presentation presentation);

    NachFile.Presentation parsePresentation(String text);

    String renderResponse(NachFile.Response response);

    /**
     * @throws NachFile.FormatException when the text is not in this layout or its control totals do not agree
     *                                  with its rows — the whole file is refused, no row is processed
     */
    NachFile.Response parseResponse(String text);

    /**
     * The layout for a code and encoding.
     *
     * @throws IllegalArgumentException for a layout the product does not have
     */
    static NachFileFormat of(String code, String encoding) {
        if (!GenericNachFormat.CODE.equals(code)) {
            throw new IllegalArgumentException("no NACH file layout '" + code + "': only GENERIC is built; the sponsor bank's"
                    + " layout has to be added as another NachFileFormat");
        }
        return new GenericNachFormat(GenericNachFormat.Encoding.valueOf(encoding));
    }
}
